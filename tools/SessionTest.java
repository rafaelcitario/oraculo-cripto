import com.oraculo.app.Session;
import com.oraculo.core.*;
import com.oraculo.net.BinanceSource;
import com.oraculo.net.CoinGeckoSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Teste de integração do ciclo completo (sem rede, sem Android), com um relógio e uma corretora falsos.
 * Compilação/execução: veja tools/run-tests.sh
 */
public class SessionTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  [OK]   " : "  [FALHA] ") + msg);
        if (!ok) failures++;
    }

    static final long T0 = (1_700_000_000_000L / 60_000L) * 60_000L;
    static long now = T0 + 1500L * 60_000L;

    /** Preços sintéticos consistentes entre chamadas, com regimes de tendência. */
    static class FakePrices implements Session.PriceSource {
        final Map<String, double[]> data = new HashMap<>();
        boolean fail = false;
        final Set<String> bases = new HashSet<>(Arrays.asList("BTC", "ETH", "SOL", "BNB", "DOGE", "ADA", "USDT"));

        double[] series(String sym) {
            return data.computeIfAbsent(sym, s -> {
                Random r = new Random(s.hashCode());
                double[] p = new double[5000];
                double price = 100 + r.nextInt(900), regime = 1;
                for (int i = 0; i < p.length; i++) {
                    if (r.nextDouble() < 1.0 / 40) regime = r.nextBoolean() ? 1 : -1;
                    price *= Math.exp(regime * 0.00015 + r.nextGaussian() * 0.0008);
                    p[i] = price;
                }
                return p;
            });
        }

        public Set<String> tradableBases() throws IOException {
            if (fail) throw new IOException("rede fora do ar");
            return bases;
        }

        public List<Candle> klines(String symbol, int limit) throws IOException {
            if (fail) throw new IOException("rede fora do ar");
            if (!symbol.endsWith("USDT")) throw new IOException("símbolo inválido");
            double[] p = series(symbol.substring(0, symbol.length() - 4));
            int cur = (int) ((now - T0) / 60_000L); // vela em andamento
            List<Candle> out = new ArrayList<>();
            for (int i = Math.max(1, cur - limit + 1); i <= cur; i++) {
                out.add(new Candle(T0 + i * 60_000L, p[i - 1], Math.max(p[i - 1], p[i]), Math.min(p[i - 1], p[i]), p[i], 10));
            }
            return out;
        }
    }

    static Candidate cand(String id, String sym, double price, double mcap, double vol, double hi, double lo, double c1, double c24) {
        Candidate c = new Candidate();
        c.id = id; c.symbol = sym; c.name = id; c.price = price; c.marketCap = mcap; c.volume24h = vol;
        c.high24h = hi; c.low24h = lo; c.ch1h = c1; c.ch24h = c24;
        return c;
    }

    static class MapStore implements Session.Store {
        final Map<String, String> m = new HashMap<>();
        public String get(String k) { return m.get(k); }
        public void put(String k, String v) { m.put(k, v); }
        public void remove(String k) { m.remove(k); }
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("oraculo-test").toFile();
        MapStore store = new MapStore();
        FakePrices prices = new FakePrices();
        List<Candidate> mk = new ArrayList<>();
        mk.add(cand("bitcoin", "BTC", 100, 2e12, 3e10, 103, 97, 0.4, 1.5));
        mk.add(cand("ethereum", "ETH", 100, 4e11, 1.5e10, 104.5, 100, 0.5, 2.0));
        mk.add(cand("solana", "SOL", 100, 5e10, 2e9, 104, 100, 0.8, 2.0));
        mk.add(cand("tether", "USDT", 1, 1e11, 6e10, 1.001, 0.999, 0, 0));
        mk.add(cand("dogecoin", "DOGE", 1, 2e10, 1e9, 1.06, 1.0, 0.2, 1.0));
        Session.MarketSource market = () -> mk;
        Session s = new Session(dir, store, market, prices, () -> now);

        System.out.println("== 1) Inicialização: escolhe o ativo do dia e pré-treina ==");
        s.step();
        System.out.println("  status: " + s.status());
        check(s.isReady(), "sessão pronta após o primeiro passo");
        check(s.pick() != null, "ativo escolhido: " + (s.pick() == null ? "-" : s.pick().symbol + " (" + s.pick().reasons + ")"));
        check(s.ranking().stream().noneMatch(c -> c.symbol.equals("USDT")), "stablecoin fora do ranking");
        check(s.oracle.warmStats.total > 2000, "pré-treino usou histórico de várias moedas (" + s.oracle.warmStats.total + " previsões prequential)");
        String firstId = s.pick().id;

        System.out.println("== 2) 600 minutos ao vivo (3 passos por minuto, sem processar vela duas vezes) ==");
        long before = s.oracle.stats.total;
        for (int m = 0; m < 600; m++) {
            for (int sub = 0; sub < 3; sub++) {
                now += 20_000L;
                s.step();
            }
        }
        long made = s.oracle.stats.total - before;
        System.out.printf("  previsões resolvidas ao vivo: %d | acerto %.1f%% | recompensa %+.1f | carteira %.2f (%d operações)%n",
                made, s.oracle.stats.accuracy() * 100, s.oracle.stats.reward, s.trader.equity(), s.trader.tradeCount());
        check(made >= 590 && made <= 600, "uma previsão resolvida por minuto, sem duplicatas (" + made + ")");
        check(s.status().startsWith("Ao vivo"), "status: " + s.status());
        check(s.livePrice() > 0, "preço ao vivo atualizado");
        check(s.oracle.stats.accuracy() > 0.52, "acerto acima de 52% neste mercado sintético com padrão");

        System.out.println("== 3) Lacuna de 40 min sem executar (celular dormiu): deve recuperar tudo ==");
        long t1 = s.oracle.stats.total;
        now += 40L * 60_000L;
        s.step();
        long lastCandle = s.oracle.lastLiveCandleTime();
        long expectedLast = ((now - T0) / 60_000L - 1) * 60_000L + T0;
        check(lastCandle == expectedLast, "última vela processada é a mais recente fechada");
        check(s.oracle.stats.total - t1 >= 35, "previsões pendentes foram resolvidas na recuperação (" + (s.oracle.stats.total - t1) + ")");

        System.out.println("== 4) Queda de rede não derruba nada ==");
        prices.fail = true;
        now += 60_000L;
        s.step();
        System.out.println("  status: " + s.status());
        check(s.status().contains("Sem dados"), "informa a falha");
        check(s.isReady(), "continua pronta (falha transitória)");
        prices.fail = false;
        now += 60_000L;
        s.step();
        check(s.status().startsWith("Ao vivo"), "volta ao normal sozinha");

        System.out.println("== 5) Reescolher ativo ==");
        double eqBefore = s.trader.equity();
        s.requestRescan();
        s.step();
        System.out.println("  novo ativo: " + s.pick().symbol);
        check(!s.pick().id.equals(firstId), "trocou para outra candidata");
        check(!s.trader.isOpen(), "posição simulada foi encerrada na troca");
        check(Math.abs(s.trader.equity() - eqBefore) < eqBefore * 0.02, "carteira segue coerente após a troca");
        check(s.isReady(), "pronta no novo ativo");

        System.out.println("== 6) Persistência: reabrir o app no mesmo dia ==");
        s.saveState();
        long totalBefore = s.oracle.stats.total;
        double eq = s.trader.equity();
        String pickBefore = s.pick().id;
        Session s2 = new Session(dir, store, market, prices, () -> now);
        s2.step();
        check(s2.isReady(), "nova sessão sobe");
        check(s2.pick().id.equals(pickBefore), "mantém o ativo escolhido do dia (" + s2.pick().symbol + ")");
        check(s2.oracle.stats.total == totalBefore, "placar de acertos preservado (" + totalBefore + ")");
        check(Math.abs(s2.trader.equity() - eq) < 1e-6, "carteira preservada (" + String.format("%.2f", eq) + ")");

        System.out.println("== 7) Dia seguinte: novo ativo do dia ==");
        now += 24L * 3600_000L + 120_000L;
        Session s3 = new Session(dir, store, market, prices, () -> now);
        s3.step();
        check(s3.isReady(), "sessão sobe no dia seguinte (histórico de 1000 min disponível)");
        System.out.println("  ativo de hoje: " + s3.pick().symbol);

        System.out.println("== 8) Parsers de rede ==");
        String cg = "[{\"id\":\"bitcoin\",\"symbol\":\"btc\",\"name\":\"Bitcoin\",\"current_price\":67000.5,"
                + "\"market_cap\":1.3e12,\"total_volume\":2.5e10,\"high_24h\":68000,\"low_24h\":65500,"
                + "\"price_change_percentage_24h\":1.2,\"price_change_percentage_1h_in_currency\":null,"
                + "\"price_change_percentage_24h_in_currency\":1.25,\"price_change_percentage_7d_in_currency\":-3.1},"
                + "{\"id\":\"broken\",\"symbol\":\"\",\"current_price\":null}]";
        List<Candidate> parsed = CoinGeckoSource.parse(cg);
        check(parsed.size() == 1 && parsed.get(0).symbol.equals("BTC") && parsed.get(0).ch1h == 0
                && Math.abs(parsed.get(0).ch24h - 1.25) < 1e-9 && Math.abs(parsed.get(0).rangePct() - 3.73) < 0.01,
                "CoinGecko: campos, null tolerado e item inválido descartado");
        String bn = "[[1700000040000,\"100.0\",\"101.5\",\"99.5\",\"101.0\",\"12.5\",1700000099999,\"1262.5\",50,\"6\",\"600\",\"0\"],"
                + "[1700000100000,\"101.0\",\"102.0\",\"100.9\",\"101.7\",\"8.1\",1700000159999,\"820\",30,\"4\",\"400\",\"0\"]]";
        List<Candle> kl = BinanceSource.parseKlines(bn);
        check(kl.size() == 2 && kl.get(1).close == 101.7 && kl.get(0).openTime == 1700000040000L && kl.get(0).volume == 12.5,
                "Binance: velas lidas corretamente");
        boolean threw = false;
        try { BinanceSource.parseKlines("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"); } catch (IOException e) { threw = true; }
        check(threw, "Binance: resposta de erro vira IOException (não trava)");

        System.out.println();
        System.out.println(failures == 0 ? "TUDO CERTO" : (failures + " FALHA(S)"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
