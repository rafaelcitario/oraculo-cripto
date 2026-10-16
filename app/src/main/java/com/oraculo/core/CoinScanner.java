package com.oraculo.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pontua as moedas por "operabilidade para um oráculo de curtíssimo prazo":
 * precisa se mexer o bastante (amplitude), ter liquidez (volume) e giro saudável.
 * IMPORTANTE: isto escolhe a moeda mais ATIVA e líquida, não a que vai dar lucro.
 */
public final class CoinScanner {
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "USDT", "USDC", "DAI", "TUSD", "FDUSD", "USDE", "USDS", "BUSD", "PYUSD", "USDD", "GUSD",
            "FRAX", "LUSD", "SUSD", "EURC", "EURT", "USD1", "USDT0", "XAUT", "PAXG",
            "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "WBETH", "CBBTC", "RETH", "BSC-USD", "BTCB", "SOLVBTC"));

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private CoinScanner() {}

    /**
     * @param tradable símbolos (MAIÚSCULOS) que têm par USDT na fonte de preços de 1 min; null = sem filtro
     */
    public static List<Candidate> rank(List<Candidate> in, Set<String> tradable) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            if (EXCLUDED.contains(c.symbol.toUpperCase(Locale.ROOT))) continue;
            if (tradable != null && !tradable.contains(c.symbol.toUpperCase(Locale.ROOT))) continue;
            double range = c.rangePct();
            if (range < 0.8 || c.volume24h < 5_000_000) continue; // parada demais ou sem liquidez
            score(c, range);
            out.add(c);
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    static void score(Candidate c, double range) {
        double ln = Math.log(range / 4.0);
        double trade = Math.exp(-(ln * ln) / (2 * 0.6 * 0.6)); // melhor perto de 4% de amplitude
        double liq = clamp((Math.log10(Math.max(c.volume24h, 1)) - 6.0) / 3.0, 0, 1); // 1 mi -> 0, 1 bi -> 1
        double turnoverRatio = c.marketCap > 0 ? c.volume24h / c.marketCap : 0;
        double turn = clamp(turnoverRatio / 0.10, 0, 1) * (turnoverRatio > 1.5 ? 0.5 : 1.0);
        double align = (c.ch1h * c.ch24h > 0) ? Math.min(1.0, Math.abs(c.ch1h) / 1.0) : 0;

        c.score = 100.0 * (0.40 * trade + 0.35 * liq + 0.15 * turn + 0.10 * align);

        c.reasons.clear();
        Locale br = PT_BR;
        c.reasons.add(String.format(br, "amplitude 24h de %.1f%%%s", range,
                trade > 0.8 ? " (ideal para curto prazo)" : (range > 8 ? " (muito volátil)" : " (movimento moderado)")));
        c.reasons.add(String.format(br, "volume 24h de US$ %s", human(c.volume24h)));
        if (turn > 0.6) c.reasons.add("giro de negociação saudável");
        if (align > 0.5) c.reasons.add("1h e 24h na mesma direção");
    }

    static String human(double v) {
        if (v >= 1e9) return String.format(PT_BR, "%.1f bi", v / 1e9);
        if (v >= 1e6) return String.format(PT_BR, "%.0f mi", v / 1e6);
        return String.format(PT_BR, "%.0f", v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
