package com.oraculo.app;

import java.util.Locale;

/** Formatação em português do Brasil. */
final class Fmt {
    static final Locale BR = Locale.forLanguageTag("pt-BR");

    private Fmt() {}

    static String price(double p) {
        if (p >= 1000) return String.format(BR, "%,.2f", p);
        if (p >= 1) return String.format(BR, "%,.3f", p);
        if (p >= 0.01) return String.format(BR, "%.5f", p);
        return String.format(BR, "%.8f", p);
    }

    /** 0.123 -> "12,3%" */
    static String pct(double fraction, int decimals) {
        return String.format(BR, "%." + decimals + "f%%", fraction * 100.0);
    }

    /** 0.0123 -> "+1,23%" */
    static String signedPct(double fraction, int decimals) {
        return String.format(BR, "%+." + decimals + "f%%", fraction * 100.0);
    }

    static String money(double v) {
        return String.format(BR, "US$ %,.2f", v);
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(BR, "%d:%02d", s / 60, s % 60);
    }
}
