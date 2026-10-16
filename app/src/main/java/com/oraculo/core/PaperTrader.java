package com.oraculo.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Carteira SIMULADA. Nenhum dinheiro real e nenhuma ordem real: só contas.
 * Compra (long) quando o oráculo prevê alta com probabilidade >= enterP, sai após 5 min ou no stop.
 * Inclui taxa (0,10% por lado) e derrapagem (0,02%) para o resultado não ser otimista demais.
 */
public final class PaperTrader {
    public static final class Trade {
        public long entryTime, exitTime;
        public double entryPrice, exitPrice, pnl, pct;
        public String reason = "";
    }

    public final double startCash;
    public double feeRate = 0.001;
    public double slip = 0.0002;
    public double stakeFrac = 0.5;
    public double enterP = 0.56;
    public double stopLoss = 0.006;

    private double cash, qty, entryPrice, entryCost, lastPrice, firstPrice;
    private long exitTime;
    private boolean open, justClosed;
    private final List<Trade> trades = new ArrayList<>();
    private long entryTimeOpen;
    private int totalTrades, winTrades;

    public PaperTrader(double startCash) {
        this.startCash = startCash;
        this.cash = startCash;
    }

    public synchronized void onCandle(Candle c, Prediction newPred) {
        lastPrice = c.close;
        if (firstPrice == 0) firstPrice = c.close;
        justClosed = false;

        if (open) {
            double chg = c.close / entryPrice - 1;
            if (chg <= -stopLoss) close(c.close, c.openTime, "stop");
            else if (c.openTime >= exitTime) close(c.close, c.openTime, "5 min");
        }
        if (!open && !justClosed && newPred != null && newPred.pUp >= enterP) {
            double price = c.close * (1 + slip);
            double stake = Math.min(cash, equityLocked() * stakeFrac);
            if (stake > 1e-9) {
                qty = stake * (1 - feeRate) / price;
                cash -= stake;
                entryCost = stake;
                entryPrice = price;
                entryTimeOpen = c.openTime;
                exitTime = c.openTime + Oracle.HORIZON_MS;
                open = true;
            }
        }
    }

    /** Fecha a posição aberta (ex.: ao trocar de moeda) e reinicia o benchmark de "comprar e segurar". */
    public synchronized void switchAsset() {
        if (open) close(lastPrice, entryTimeOpen, "troca de ativo");
        firstPrice = 0;
        lastPrice = 0;
    }

    private void close(double rawPrice, long time, String reason) {
        double px = rawPrice * (1 - slip);
        double proceeds = qty * px * (1 - feeRate);
        Trade t = new Trade();
        t.entryTime = entryTimeOpen;
        t.exitTime = time;
        t.entryPrice = entryPrice;
        t.exitPrice = px;
        t.pnl = proceeds - entryCost;
        t.pct = t.pnl / entryCost;
        t.reason = reason;
        trades.add(t);
        totalTrades++;
        if (t.pnl > 0) winTrades++;
        if (trades.size() > 300) trades.remove(0);
        cash += proceeds;
        qty = 0;
        open = false;
        justClosed = true;
    }

    private double equityLocked() {
        return cash + qty * lastPrice;
    }

    public synchronized double equity() { return equityLocked(); }
    public synchronized double pnl() { return equityLocked() - startCash; }
    public synchronized boolean isOpen() { return open; }
    public synchronized double entryPriceNow() { return entryPrice; }

    /** Quanto teria hoje quem apenas comprou a moeda no início da sessão (sem taxas). */
    public synchronized double buyAndHoldPct() {
        return firstPrice > 0 && lastPrice > 0 ? lastPrice / firstPrice - 1 : 0;
    }

    public synchronized int tradeCount() { return totalTrades; }

    public synchronized double winRate() {
        return totalTrades == 0 ? 0 : (double) winTrades / totalTrades;
    }

    public synchronized List<Trade> trades() { return new ArrayList<>(trades); }

    public synchronized void save(DataOutputStream out) throws IOException {
        out.writeInt(2);
        out.writeInt(totalTrades);
        out.writeInt(winTrades);
        out.writeDouble(cash);
        out.writeDouble(qty);
        out.writeDouble(entryPrice);
        out.writeDouble(entryCost);
        out.writeDouble(lastPrice);
        out.writeLong(exitTime);
        out.writeLong(entryTimeOpen);
        out.writeBoolean(open);
        out.writeInt(trades.size());
        for (Trade t : trades) {
            out.writeLong(t.entryTime); out.writeLong(t.exitTime);
            out.writeDouble(t.entryPrice); out.writeDouble(t.exitPrice);
            out.writeDouble(t.pnl); out.writeDouble(t.pct);
            out.writeUTF(t.reason);
        }
    }

    public synchronized void load(DataInputStream in) throws IOException {
        if (in.readInt() != 2) throw new IOException("versão de arquivo desconhecida");
        totalTrades = in.readInt();
        winTrades = in.readInt();
        cash = in.readDouble();
        qty = in.readDouble();
        entryPrice = in.readDouble();
        entryCost = in.readDouble();
        lastPrice = in.readDouble();
        exitTime = in.readLong();
        entryTimeOpen = in.readLong();
        open = in.readBoolean();
        int n = in.readInt();
        trades.clear();
        for (int i = 0; i < n; i++) {
            Trade t = new Trade();
            t.entryTime = in.readLong(); t.exitTime = in.readLong();
            t.entryPrice = in.readDouble(); t.exitPrice = in.readDouble();
            t.pnl = in.readDouble(); t.pct = in.readDouble();
            t.reason = in.readUTF();
            trades.add(t);
        }
        firstPrice = 0;
    }
}
