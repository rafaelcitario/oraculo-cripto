import com.oraculo.core.*;

import java.io.*;
import java.util.*;

/**
 * Autoteste do núcleo (roda em qualquer JDK, sem Android):
 *   javac -d out app/src/main/java/com/oraculo/core/*.java tools/CoreSelfTest.java
 *   java -cp out CoreSelfTest
 */
public class CoreSelfTest {
    static int failures = 0;

    static void check(boolean ok, String msg) {
        System.out.println((ok ? "  [OK]   " : "  [FALHA] ") + msg);
        if (!ok) failures++;
    }

    interface Gen { double next(Random r, double[] state); }

    static List<Candle> series(int n, long seed, Gen g) {
        Random r = new Random(seed);
        double[] st = new double[]{0, 1}; // [regime, aux]
        double price = 100;
        long t0 = 1_700_000_040_000L - (1_700_000_040_000L % 60_000L);
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double ret = g.next(r, st);
            double open = price;
            price = price * Math.exp(ret);
            double vol = Math.exp(r.nextGaussian() * 0.5) * 10;
            out.add(new Candle(t0 + i * 60_000L, open, Math.max(open, price), Math.min(open, price), price, vol));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1) Passeio aleatório (não existe padrão): o oráculo NÃO pode fingir que acerta ==");
        List<Candle> rw = series(30000, 1, (r, st) -> r.nextGaussian() * 0.0008);
        Oracle o1 = new Oracle();
        PaperTrader pt = new PaperTrader(1000);
        for (Candle c : rw) {
            Prediction p = o1.onLiveCandle(c);
            pt.onCandle(c, p);
        }
        double acc1 = o1.stats.accuracy();
        double[] w1 = o1.stats.wilson();
        System.out.printf("  previsões: %d | acerto: %.2f%% | IC95 (descontada a sobreposição): [%.2f%%, %.2f%%] | momentum: %.2f%% | sempre-alta: %.2f%%%n",
                o1.stats.total, acc1 * 100, w1[0] * 100, w1[1] * 100,
                o1.stats.momentumAccuracy() * 100, o1.stats.alwaysUpAccuracy() * 100);
        System.out.printf("  carteira simulada: início 1000.00 -> fim %.2f (%d operações, %.0f%% vencedoras)%n",
                pt.equity(), pt.tradeCount(), pt.winRate() * 100);
        check(Math.abs(acc1 - 0.5) < 0.02, "acerto fica perto de 50% (sem vantagem inventada)");
        check(!o1.stats.significantEdge(), "placar NÃO declara vantagem estatística em dados aleatórios");
        check(pt.equity() < 1000 || pt.tradeCount() == 0, "taxas corroem a carteira sem vantagem real (comportamento realista)");

        System.out.println("== 2) Mercado com regimes de tendência (padrão real escondido): o oráculo deve aprender ==");
        final double drift = 0.00015;
        List<Candle> rg = series(40000, 2, (r, st) -> {
            if (r.nextDouble() < 1.0 / 40) st[0] = r.nextBoolean() ? 1 : -1;
            if (st[0] == 0) st[0] = 1;
            return st[0] * drift + r.nextGaussian() * 0.0008;
        });
        Oracle o2 = new Oracle();
        long hitsHalf = 0, totalHalf = 0;
        for (int i = 0; i < rg.size(); i++) {
            o2.onLiveCandle(rg.get(i));
            if (i == rg.size() / 2) { hitsHalf = o2.stats.hits; totalHalf = o2.stats.total; }
        }
        double accLate = (double) (o2.stats.hits - hitsHalf) / (o2.stats.total - totalHalf);
        System.out.printf("  acerto na 2a metade (aprendido a frio, sempre prevendo antes de ver o resultado): %.2f%%%n", accLate * 100);
        System.out.printf("  acerto com alta convicção (p>=0.60 ou <=0.40): %.2f%% em %d previsões%n",
                o2.stats.hiAccuracy() * 100, o2.stats.hiTotal);
        System.out.printf("  recompensa acumulada: %.1f%n", o2.stats.reward);
        check(accLate > 0.54, "acerto da segunda metade > 54% (aprendeu o padrão)");
        check(o2.stats.significantEdge(), "placar declara vantagem estatística quando ela existe");
        check(o2.stats.reward > 0, "recompensa acumulada positiva");

        System.out.println("== 3) Pré-treino com histórico + treino consolidado ==");
        Oracle o3 = new Oracle();
        o3.pretrain(rg.subList(0, 20000));
        o3.consolidate(2);
        System.out.printf("  acerto prequential no pré-treino: %.2f%% (n=%d)%n", o3.warmStats.accuracy() * 100, o3.warmStats.total);
        o3.seedLive(rg.subList(19500, 20000));
        int ok = 0, n = 0;
        for (int i = 20000; i < 30000; i++) {
            o3.onLiveCandle(rg.get(i));
        }
        System.out.printf("  acerto ao vivo logo após o pré-treino: %.2f%% (n=%d)%n", o3.stats.accuracy() * 100, o3.stats.total);
        check(o3.stats.accuracy() > 0.54, "modelo pré-treinado já começa acima de 54% ao vivo");

        System.out.println("== 4) Salvar e carregar ==");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        o3.save(new DataOutputStream(bos));
        Oracle o4 = new Oracle();
        o4.load(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
        o3.seedLive(rg.subList(29500, 30000));
        o4.seedLive(rg.subList(29500, 30000));
        double pa = o3.currentPUp(), pb = o4.currentPUp();
        System.out.printf("  p(alta) antes: %.6f | depois de recarregar: %.6f | arquivo: %d KB%n", pa, pb, bos.size() / 1024);
        check(Math.abs(pa - pb) < 1e-12, "modelo recarregado dá exatamente a mesma previsão");
        check(o4.stats.total == o3.stats.total && o4.stats.hits == o3.stats.hits, "placar preservado");

        ByteArrayOutputStream tb = new ByteArrayOutputStream();
        pt.save(new DataOutputStream(tb));
        PaperTrader pt2 = new PaperTrader(1000);
        pt2.load(new DataInputStream(new ByteArrayInputStream(tb.toByteArray())));
        check(Math.abs(pt2.equity() - pt.equity()) < 1e-9 && pt2.tradeCount() == pt.tradeCount(), "carteira simulada preservada");

        System.out.println("== 5) Robustez a dados ruins ==");
        Oracle o5 = new Oracle();
        List<Candle> w = rw.subList(0, 200);
        for (Candle c : w) o5.onLiveCandle(c);
        long before = o5.stats.total;
        check(o5.onLiveCandle(w.get(100)) == null, "vela repetida/antiga é ignorada");
        Candle gap = new Candle(w.get(199).openTime + 30 * 60_000L, 1, 1, 1, 1, 0);
        o5.onLiveCandle(gap);
        check(o5.latest() != null && o5.stats.total >= before, "buraco de 30 min não derruba o oráculo");
        List<Candle> flat = new ArrayList<>();
        for (int i = 0; i < 200; i++) flat.add(new Candle(1_700_000_040_000L + i * 60_000L, 5, 5, 5, 5, 0));
        Oracle o6 = new Oracle();
        for (Candle c : flat) o6.onLiveCandle(c);
        check(!Double.isNaN(o6.currentPUp()), "preço totalmente parado não gera NaN");
        check(o6.stats.total == 0 && o6.stats.voided > 0, "resultado sem variação é anulado, não contado como erro/acerto");

        System.out.println("== 6) Scanner ==");
        List<Candidate> cands = new ArrayList<>();
        cands.add(cand("bitcoin", "btc", 100, 2e12, 3e10, 103, 97, 0.4, 1.5));      // amplitude 6%
        cands.add(cand("tether", "usdt", 1, 1e11, 6e10, 1.001, 0.999, 0, 0));       // stablecoin
        cands.add(cand("coinx", "cx", 1, 5e7, 8e5, 1.2, 0.8, 5, 8));               // sem liquidez
        cands.add(cand("sol", "sol", 100, 5e10, 2e9, 104, 100, 0.8, 2.0));          // amplitude 4%
        cands.add(cand("ghost", "ghs", 1, 1e9, 1e8, 1.5, 0.5, 1, -1));              // fora da corretora
        Set<String> tradable = new HashSet<>(Arrays.asList("BTC", "SOL", "USDT", "CX"));
        List<Candidate> ranked = CoinScanner.rank(cands, tradable);
        for (Candidate c : ranked) System.out.printf("  %s  nota %.1f  | %s%n", c.symbol, c.score, c.reasons);
        check(ranked.size() == 2, "exclui stablecoin, moeda sem liquidez e moeda fora da corretora");
        check(ranked.get(0).symbol.equals("SOL"), "prefere amplitude ~4% e maior liquidez (SOL à frente)");

        System.out.println();
        System.out.println(failures == 0 ? "TUDO CERTO" : (failures + " FALHA(S)"));
        System.exit(failures == 0 ? 0 : 1);
    }

    static Candidate cand(String id, String sym, double price, double mcap, double vol, double hi, double lo, double c1, double c24) {
        Candidate c = new Candidate();
        c.id = id; c.symbol = sym.toUpperCase(); c.name = id;
        c.price = price; c.marketCap = mcap; c.volume24h = vol; c.high24h = hi; c.low24h = lo;
        c.ch1h = c1; c.ch24h = c24;
        return c;
    }
}
