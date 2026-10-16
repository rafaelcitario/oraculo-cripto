package com.oraculo.core;

import java.util.List;

/**
 * Transforma os últimos 60 minutos de velas em 14 números sem escala de preço
 * (retornos normalizados pela volatilidade recente), para que o mesmo modelo
 * sirva para qualquer moeda. Usa apenas velas até o índice i (sem olhar o futuro).
 */
public final class Features {
    public static final int N = 14;
    public static final int MIN_HISTORY = 61;
    /** Índice da feature "retorno de 5 min" (usada como baseline de momentum). */
    public static final int IDX_MOM5 = 2;
    private static final double SIGMA_FLOOR = 2e-5;
    private static final int[] LAGS = {1, 3, 5, 10, 15, 30, 60};

    private Features() {}

    public static double[] compute(List<Candle> h, int i) {
        if (i < MIN_HISTORY - 1) return null;
        double c = h.get(i).close;
        if (!(c > 0)) return null;

        // retornos log de 1 min, do mais novo para o mais antigo
        double[] r = new double[60];
        for (int k = 0; k < 60; k++) {
            double a = h.get(i - k).close;
            double b = h.get(i - k - 1).close;
            if (!(a > 0) || !(b > 0)) return null;
            r[k] = Math.log(a / b);
        }
        double sigma = Math.max(std(r, 0, 60), SIGMA_FLOOR);

        double[] f = new double[N];
        for (int j = 0; j < LAGS.length; j++) {
            int lag = LAGS[j];
            double past = h.get(i - lag).close;
            double z = Math.log(c / past) / (sigma * Math.sqrt(lag));
            f[j] = clip(z, -4, 4) / 4.0;
        }

        double s10 = Math.max(std(r, 0, 10), SIGMA_FLOOR * 0.5);
        f[7] = clip(Math.log(s10 / sigma), -1, 1);

        double gain = 0, loss = 0;
        for (int k = 0; k < 14; k++) {
            if (r[k] > 0) gain += r[k]; else loss -= r[k];
        }
        double rsi = (gain + loss) == 0 ? 50 : 100 * gain / (gain + loss);
        f[8] = (rsi - 50) / 50.0;

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        double v5 = 0, v60 = 0;
        for (int k = 0; k < 60; k++) {
            Candle cd = h.get(i - k);
            mn = Math.min(mn, cd.close);
            mx = Math.max(mx, cd.close);
            v60 += cd.volume;
            if (k < 5) v5 += cd.volume;
        }
        f[9] = mx > mn ? ((c - mn) / (mx - mn)) * 2 - 1 : 0;

        v5 /= 5.0;
        v60 /= 60.0;
        f[10] = (v60 > 0 && v5 > 0) ? clip(Math.log(v5 / v60), -1.5, 1.5) / 1.5 : 0;

        long minuteOfDay = (h.get(i).openTime / 60000L) % 1440L;
        double ang = 2 * Math.PI * (minuteOfDay / 1440.0);
        f[11] = Math.sin(ang);
        f[12] = Math.cos(ang);

        double e9 = h.get(i - 59).close, e21 = e9;
        double a9 = 2.0 / 10.0, a21 = 2.0 / 22.0;
        for (int k = 58; k >= 0; k--) {
            double v = h.get(i - k).close;
            e9 += a9 * (v - e9);
            e21 += a21 * (v - e21);
        }
        f[13] = clip(((e9 - e21) / c) / (sigma * Math.sqrt(10)), -4, 4) / 4.0;
        return f;
    }

    private static double std(double[] a, int from, int to) {
        int n = to - from;
        double m = 0;
        for (int k = from; k < to; k++) m += a[k];
        m /= n;
        double s = 0;
        for (int k = from; k < to; k++) s += (a[k] - m) * (a[k] - m);
        return Math.sqrt(s / n);
    }

    private static double clip(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
