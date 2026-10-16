package com.oraculo.core;

/** Vela de 1 minuto (já fechada). */
public final class Candle {
    public final long openTime; // ms epoch
    public final double open, high, low, close, volume;

    public Candle(long openTime, double open, double high, double low, double close, double volume) {
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }
}
