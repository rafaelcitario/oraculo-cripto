package com.oraculo.app;

import com.oraculo.core.Candidate;
import com.oraculo.core.Candle;
import com.oraculo.core.CoinScanner;
import com.oraculo.core.Oracle;
import com.oraculo.core.PaperTrader;
import com.oraculo.core.Prediction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Coordena tudo: escolhe o ativo do dia (CoinGecko), pré-treina o oráculo com histórico,
 * acompanha o preço minuto a minuto, registra previsões, aprende com os resultados e
 * opera na carteira SIMULADA. Não depende de Android (testável na JVM).
 */
public final class Session {
    public interface MarketSource { List<Candidate> markets() throws IOException; }

    public interface PriceSource {
        Set<String> tradableBases() throws IOException;
        /** Velas de 1 min, da mais antiga para a mais nova; a última ainda está em andamento. */
        List<Candle> klines(String symbol, int limit) throws IOException;
    }

    public interface Store {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
    }

    private static final String[] ANCHORS = {"BTC", "ETH", "SOL", "BNB"};
    private static final int HISTORY = 1000;
    private static final long POLL_SECONDS = 15;
    private static final long SAVE_EVERY_MS = 5 * 60_000L;

    private final File dir;
    private final Store store;
    private final MarketSource markets;
    private final PriceSource prices;
    private final LongSupplier clock;

    public final Oracle oracle = new Oracle();
    public final PaperTrader trader = new PaperTrader(1000.0);

    private final ArrayList<Candle> series = new ArrayList<>();
    private volatile String status = "Parado";
    private volatile boolean running, ready, rescan;
    private volatile Candidate pick;
    private volatile List<Candidate> ranking = Collections.emptyList();
    private volatile double livePrice;
    private volatile long lastSave;
    private boolean stateLoaded;

    private final Object stepLock = new Object();
    private final Object lifecycleLock = new Object();
    private ScheduledExecutorService exec;

    public Session(File dir, Store store, MarketSource markets, PriceSource prices, LongSupplier clock) {
        this.dir = dir;
        this.store = store;
        this.markets = markets;
        this.prices = prices;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- ciclo de vida

    public void start() {
        synchronized (lifecycleLock) {
            if (running) return;
            running = true;
            status = "Iniciando…";
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "oraculo");
                t.setDaemon(true);
                return t;
            });
            exec.scheduleWithFixedDelay(this::step, 0, POLL_SECONDS, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!running) return;
            running = false;
            if (exec != null) exec.shutdownNow();
            exec = null;
            status = "Parado";
        }
        saveState();
    }

    /** Troca para a próxima melhor candidata do ranking. */
    public void requestRescan() {
        rescan = true;
        synchronized (lifecycleLock) {
            if (exec != null) exec.execute(this::step);
        }
    }

    // ---------------------------------------------------------------- um passo do ciclo

    /** Uma iteração: prepara tudo (se preciso) ou processa as velas novas. Nunca lança exceção. */
    public void step() {
        synchronized (stepLock) {
            boolean boot = false;
            try {
                if (!stateLoaded) {
                    loadState();
                    stateLoaded = true;
                }
                if (!ready || rescan) {
                    boot = true;
                    bootstrap();
                } else {
                    tick();
                }
            } catch (Throwable t) {
                if (boot) ready = false;
                String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                status = "Sem dados agora (" + m + ") — tentando de novo";
            }
        }
    }

    private void bootstrap() throws IOException {
        boolean wasRescan = rescan;
        status = "Consultando o CoinGecko…";
        List<Candidate> all = markets.markets();
        status = "Verificando pares com preço de 1 minuto…";
        Set<String> tradable = prices.tradableBases();
        List<Candidate> ranked = CoinScanner.rank(all, tradable);
        if (ranked.isEmpty()) throw new IOException("nenhuma moeda elegível no momento");
        ranking = ranked;

        String today = today();
        String currentId = store.get("pick_id");
        Candidate chosen = null;
        if (wasRescan) {
            for (Candidate c : ranked) {
                if (!c.id.equals(currentId)) { chosen = c; break; }
            }
        } else if (today.equals(store.get("pick_date")) && currentId != null) {
            for (Candidate c : ranked) {
                if (c.id.equals(currentId)) { chosen = c; break; }
            }
            if (chosen == null) { // saiu do ranking por filtros, mas segue o escolhido do dia se ainda negociável
                String sym = store.get("pick_symbol");
                if (sym != null && tradable.contains(sym)) {
                    chosen = new Candidate();
                    chosen.id = currentId;
                    chosen.symbol = sym;
                    String nm = store.get("pick_name");
                    chosen.name = nm == null ? sym : nm;
                    chosen.reasons.add("escolhida no início do dia");
                }
            }
        }
        if (chosen == null) chosen = ranked.get(0);
        store.put("pick_date", today);
        store.put("pick_id", chosen.id);
        store.put("pick_symbol", chosen.symbol);
        store.put("pick_name", chosen.name);

        String traded = store.get("traded_id");
        if (traded != null && !traded.equals(chosen.id)) trader.switchAsset();
        store.put("traded_id", chosen.id);
        pick = chosen;

        oracle.warmStats.clear();
        Set<String> symbols = new LinkedHashSet<>(Arrays.asList(ANCHORS));
        symbols.add(chosen.symbol);
        List<Candle> pickHistory = null;
        for (String sym : symbols) {
            boolean isPick = sym.equals(chosen.symbol);
            status = "Pré-treinando com o histórico de " + sym + "…";
            try {
                List<Candle> k = prices.klines(sym + "USDT", HISTORY);
                if (k.size() > 1) k = new ArrayList<>(k.subList(0, k.size() - 1)); // descarta vela em andamento
                if (isPick) pickHistory = k;
                if (k.size() > 80) oracle.pretrain(k);
            } catch (IOException e) {
                if (isPick) throw e; // as moedas-âncora são opcionais
            }
        }
        if (pickHistory == null || pickHistory.size() < 80)
            throw new IOException("histórico insuficiente para " + chosen.symbol);

        status = "Consolidando o aprendizado…";
        oracle.consolidate(2);
        oracle.seedLive(pickHistory);
        synchronized (series) {
            series.clear();
            int from = Math.max(0, pickHistory.size() - 180);
            series.addAll(pickHistory.subList(from, pickHistory.size()));
        }
        livePrice = pickHistory.get(pickHistory.size() - 1).close;
        rescan = false;
        ready = true;
        saveState();
        status = "Ao vivo — observando " + chosen.symbol;
    }

    private void tick() throws IOException {
        Candidate p = pick;
        long last = oracle.lastLiveCandleTime();
        long now = clock.getAsLong();
        long behind = Math.max(0, (now - last) / 60_000L);
        int limit = (int) Math.max(6, Math.min(1000, behind + 3));
        List<Candle> k = prices.klines(p.symbol + "USDT", limit);
        if (k.isEmpty()) return;
        livePrice = k.get(k.size() - 1).close;

        boolean changed = false;
        for (int i = 0; i < k.size() - 1; i++) {
            Candle c = k.get(i);
            if (c.openTime <= last) continue;
            Prediction pr = oracle.onLiveCandle(c);
            trader.onCandle(c, pr);
            synchronized (series) {
                series.add(c);
                while (series.size() > 240) series.remove(0);
            }
            last = c.openTime;
            changed = true;
        }
        status = "Ao vivo — observando " + p.symbol;
        if (changed && now - lastSave > SAVE_EVERY_MS) saveState();
    }

    // ---------------------------------------------------------------- persistência

    private void loadState() {
        File of = new File(dir, "oracle.bin");
        if (of.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(of)))) {
                oracle.load(in);
            } catch (IOException e) {
                status = "Estado antigo ignorado (" + e.getMessage() + ")";
            }
        }
        File tf = new File(dir, "trader.bin");
        if (tf.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(tf)))) {
                trader.load(in);
            } catch (IOException e) {
                status = "Carteira antiga ignorada (" + e.getMessage() + ")";
            }
        }
    }

    public void saveState() {
        try {
            writeAtomically(new File(dir, "oracle.bin"), out -> oracle.save(out));
            writeAtomically(new File(dir, "trader.bin"), out -> trader.save(out));
            lastSave = clock.getAsLong();
        } catch (IOException ignored) {
            // sem espaço/permissão: tenta de novo no próximo ciclo
        }
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private void writeAtomically(File target, Writer w) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("sem pasta de dados");
        File tmp = new File(dir, target.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            w.write(out);
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) throw new IOException("não consegui gravar " + target.getName());
        }
    }

    private String today() {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    // ---------------------------------------------------------------- leitura para a tela

    public String status() { return status; }
    public boolean isRunning() { return running; }
    public boolean isReady() { return ready; }
    public Candidate pick() { return pick; }
    public List<Candidate> ranking() { return ranking; }
    public double livePrice() { return livePrice; }

    public List<Candle> seriesSnapshot() {
        synchronized (series) {
            return new ArrayList<>(series);
        }
    }
}
