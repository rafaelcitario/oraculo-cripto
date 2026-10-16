package com.oraculo.core;

/** Uma previsão "o preço daqui a 5 min será maior que agora?" e, depois, o seu resultado. */
public final class Prediction {
    public final long madeAt;      // openTime da vela em que a previsão foi feita
    public final long targetTime;  // openTime da vela que decide o resultado
    public final double price0;
    public final double pUp;       // probabilidade estimada de alta
    public final double[] x;       // features usadas (para aprender depois)
    public final double mom5;      // sinal de momentum de 5 min (baseline)

    public volatile boolean resolved;
    public volatile boolean voided; // preço igual ou dado faltando: não conta
    public volatile boolean hit;
    public volatile double price1;
    public volatile double reward;

    public Prediction(long madeAt, long targetTime, double price0, double pUp, double[] x) {
        this.madeAt = madeAt;
        this.targetTime = targetTime;
        this.price0 = price0;
        this.pUp = pUp;
        this.x = x;
        this.mom5 = x[Features.IDX_MOM5];
    }

    public boolean predictsUp() {
        return pUp >= 0.5;
    }

    /** 0 = sem convicção, 1 = máxima convicção. */
    public double confidence() {
        return Math.abs(pUp - 0.5) * 2;
    }
}
