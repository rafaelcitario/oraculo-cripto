package com.oraculo.core;

import java.util.ArrayList;
import java.util.List;

/** Moeda candidata vinda do CoinGecko, com a pontuação do scanner. */
public final class Candidate {
    public String id = "";       // id no CoinGecko (ex.: "bitcoin")
    public String symbol = "";   // MAIÚSCULO (ex.: "BTC")
    public String name = "";
    public double price, marketCap, volume24h, high24h, low24h;
    public double ch1h, ch24h, ch7d; // variações em %

    public double score;
    public final List<String> reasons = new ArrayList<>();

    /** Amplitude das últimas 24h em % do preço. */
    public double rangePct() {
        return price > 0 && high24h > 0 && low24h > 0 ? (high24h - low24h) / price * 100.0 : 0;
    }
}
