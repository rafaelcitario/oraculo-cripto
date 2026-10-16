package com.oraculo.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * O "oráculo": a cada vela fechada de 1 min ele prevê se o preço daqui a 5 min estará acima do atual.
 * Quando os 5 min passam, o resultado é conhecido e o sistema:
 *  - premia (+) ou pune (-) o placar, pesando pela convicção (acerto/erro confiante vale mais);
 *  - ajusta a rede: erro => passo de aprendizado maior; acerto => passo menor (consolidação);
 *  - guarda a amostra num buffer de replay e revisita amostras antigas.
 * Como a previsão é feita ANTES de aprender com o resultado, a taxa de acerto é uma medida honesta
 * (avaliação "prequential"), sem vazamento do futuro.
 */
public final class Oracle {
    public static final int HORIZON_MIN = 5;
    public static final long HORIZON_MS = HORIZON_MIN * 60_000L;

    private static final double LR = 0.02;
    private static final double L2 = 1e-4;
    private static final double HIT_MULT = 0.7;   // acerto: reforço leve
    private static final double MISS_MULT = 1.3;  // erro: punição maior
    private static final int REPLAY_STEPS = 4;
    private static final int REPLAY_CAP = 3000;
    private static final int HIST_CAP = 400;
    private static final int RECENT_CAP = 180;
    private static final int FORMAT = 1;

    private static final class Stream {
        final ArrayList<Candle> hist = new ArrayList<>();
        final ArrayDeque<Prediction> pending = new ArrayDeque<>();
    }

    private final Mlp net = new Mlp(Features.N, 16, 42L);
    private final Random rng = new Random(7);
    public final Stats stats = new Stats();      // desempenho ao vivo
    public final Stats warmStats = new Stats();  // desempenho durante o pré-treino com histórico
    private final Stream live = new Stream();
    private final ArrayDeque<Prediction> recent = new ArrayDeque<>();

    private final double[][] replayX = new double[REPLAY_CAP][];
    private final int[] replayY = new int[REPLAY_CAP];
    private int replayN = 0, replayPos = 0;

    /** Treina com um histórico (sequencialmente, sem olhar o futuro). Pode ser chamado para várias moedas. */
    public synchronized void pretrain(List<Candle> history) {
        Stream s = new Stream();
        for (Candle c : history) step(s, c, warmStats, false);
    }

    /** Releituras do buffer de replay (aprendizado extra, sem alterar as estatísticas). */
    public synchronized void consolidate(int epochs) {
        for (int e = 0; e < epochs; e++) {
            if (replayN == 0) return;
            int[] idx = new int[replayN];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            for (int i = idx.length - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
            }
            for (int i : idx) net.train(replayX[i], replayY[i], LR * 0.4, L2);
        }
    }

    /** Carrega histórico da moeda atual no fluxo ao vivo, sem treinar. */
    public synchronized void seedLive(List<Candle> history) {
        live.hist.clear();
        live.pending.clear();
        recent.clear();
        int from = Math.max(0, history.size() - HIST_CAP);
        for (int i = from; i < history.size(); i++) live.hist.add(history.get(i));
    }

    /** Alimenta uma vela fechada nova. Devolve a nova previsão (ou null se ainda não há histórico). */
    public synchronized Prediction onLiveCandle(Candle c) {
        return step(live, c, stats, true);
    }

    public synchronized long lastLiveCandleTime() {
        return live.hist.isEmpty() ? 0 : live.hist.get(live.hist.size() - 1).openTime;
    }

    public synchronized List<Prediction> recentPredictions() {
        return new ArrayList<>(recent);
    }

    public synchronized Prediction latest() {
        return recent.isEmpty() ? null : recent.peekLast();
    }

    /** Probabilidade de alta para o estado atual do fluxo ao vivo (sem registrar previsão). */
    public synchronized double currentPUp() {
        if (live.hist.isEmpty()) return 0.5;
        double[] x = Features.compute(live.hist, live.hist.size() - 1);
        return x == null ? 0.5 : net.predict(x);
    }

    private Prediction step(Stream s, Candle c, Stats st, boolean keepRecent) {
        if (!s.hist.isEmpty()) {
            long last = s.hist.get(s.hist.size() - 1).openTime;
            if (c.openTime <= last) return null;                  // vela repetida/antiga
            if (c.openTime - last > 3 * 60_000L) {                 // buraco nos dados: recomeça
                s.hist.clear();
                s.pending.clear();
            }
        }
        s.hist.add(c);
        if (s.hist.size() > HIST_CAP) s.hist.remove(0);

        while (!s.pending.isEmpty() && s.pending.peekFirst().targetTime <= c.openTime) {
            Prediction p = s.pending.pollFirst();
            if (c.openTime - p.targetTime > 2 * 60_000L) {
                p.voided = true;
                p.resolved = true;
                st.voided++;
                continue;
            }
            resolve(p, c.close, st);
        }

        double[] x = Features.compute(s.hist, s.hist.size() - 1);
        if (x == null) return null;
        Prediction p = new Prediction(c.openTime, c.openTime + HORIZON_MS, c.close, net.predict(x), x);
        s.pending.addLast(p);
        if (keepRecent) {
            recent.addLast(p);
            if (recent.size() > RECENT_CAP) recent.pollFirst();
        }
        return p;
    }

    private void resolve(Prediction p, double price1, Stats st) {
        p.price1 = price1;
        if (price1 == p.price0) {
            p.voided = true;
            p.resolved = true;
            st.voided++;
            return;
        }
        boolean up = price1 > p.price0;
        boolean hit = p.predictsUp() == up;
        double conf = p.confidence();
        double reward = (hit ? 1.0 : -1.0) * (0.5 + conf);
        p.hit = hit;
        p.reward = reward;
        p.resolved = true;

        boolean momHit = (p.mom5 >= 0) == up;
        st.record(hit, conf, reward, momHit, up);

        int y = up ? 1 : 0;
        net.train(p.x, y, LR * (hit ? HIT_MULT : MISS_MULT), L2);
        addReplay(p.x, y);
        for (int k = 0; k < REPLAY_STEPS; k++) {
            int i = rng.nextInt(replayN);
            net.train(replayX[i], replayY[i], LR * 0.5, L2);
        }
    }

    private void addReplay(double[] x, int y) {
        replayX[replayPos] = x;
        replayY[replayPos] = y;
        replayPos = (replayPos + 1) % REPLAY_CAP;
        if (replayN < REPLAY_CAP) replayN++;
    }

    public synchronized void save(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT);
        net.save(out);
        stats.save(out);
        warmStats.save(out);
        out.writeInt(replayN);
        out.writeInt(replayPos);
        for (int i = 0; i < replayN; i++) {
            for (double v : replayX[i]) out.writeDouble(v);
            out.writeInt(replayY[i]);
        }
    }

    public synchronized void load(DataInputStream in) throws IOException {
        if (in.readInt() != FORMAT) throw new IOException("versão de arquivo desconhecida");
        net.load(in);
        stats.load(in);
        warmStats.load(in);
        replayN = in.readInt();
        replayPos = in.readInt();
        if (replayN < 0 || replayN > REPLAY_CAP || replayPos < 0 || replayPos >= REPLAY_CAP)
            throw new IOException("arquivo corrompido");
        for (int i = 0; i < replayN; i++) {
            double[] x = new double[Features.N];
            for (int k = 0; k < x.length; k++) x[k] = in.readDouble();
            replayX[i] = x;
            replayY[i] = in.readInt();
        }
    }
}
