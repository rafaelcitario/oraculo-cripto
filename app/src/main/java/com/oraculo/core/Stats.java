package com.oraculo.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/** Placar honesto do oráculo: acertos, recompensa acumulada e comparação com baselines bobos. */
public final class Stats {
    public static final int WINDOW = 100;

    public long total, hits, hiTotal, hiHits, momHits, upHits, voided;
    public double reward;
    private final ArrayDeque<Boolean> ring = new ArrayDeque<>();

    void record(boolean hit, double conf, double rew, boolean momHit, boolean up) {
        total++;
        if (hit) hits++;
        if (conf >= 0.2) { // p >= 0.60 ou p <= 0.40
            hiTotal++;
            if (hit) hiHits++;
        }
        if (momHit) momHits++;
        if (up) upHits++;
        reward += rew;
        ring.addLast(hit);
        if (ring.size() > WINDOW) ring.pollFirst();
    }

    public double accuracy() { return total == 0 ? 0 : (double) hits / total; }
    public double hiAccuracy() { return hiTotal == 0 ? 0 : (double) hiHits / hiTotal; }
    public double momentumAccuracy() { return total == 0 ? 0 : (double) momHits / total; }
    public double alwaysUpAccuracy() { return total == 0 ? 0 : (double) upHits / total; }

    public void clear() {
        total = hits = hiTotal = hiHits = momHits = upHits = voided = 0;
        reward = 0;
        ring.clear();
    }

    public int rollingN() { return ring.size(); }

    public double rollingAccuracy() {
        if (ring.isEmpty()) return 0;
        int ok = 0;
        for (boolean b : ring) if (b) ok++;
        return (double) ok / ring.size();
    }

    /**
     * Intervalo de confiança de 95% (Wilson) para a taxa de acerto: {inferior, superior}.
     * Como há uma previsão por minuto olhando 5 min à frente, previsões vizinhas compartilham
     * quase o mesmo futuro e NÃO são independentes. Por isso o tamanho efetivo da amostra é
     * total / horizonte; sem esse desconto o placar "enxergaria" vantagem em dados aleatórios.
     */
    public double[] wilson() {
        if (total == 0) return new double[]{0, 1};
        double z = 1.96, n = Math.max(1.0, (double) total / Oracle.HORIZON_MIN), p = (double) hits / total;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2 * n)) / denom;
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
        return new double[]{centre - margin, centre + margin};
    }

    /** Só vira "true" quando o limite inferior do IC95% passa de 50% (com amostra mínima). */
    public boolean significantEdge() {
        return total >= 500 && wilson()[0] > 0.5;
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeLong(total); out.writeLong(hits); out.writeLong(hiTotal); out.writeLong(hiHits);
        out.writeLong(momHits); out.writeLong(upHits); out.writeLong(voided);
        out.writeDouble(reward);
        out.writeInt(ring.size());
        for (boolean b : ring) out.writeBoolean(b);
    }

    public void load(DataInputStream in) throws IOException {
        total = in.readLong(); hits = in.readLong(); hiTotal = in.readLong(); hiHits = in.readLong();
        momHits = in.readLong(); upHits = in.readLong(); voided = in.readLong();
        reward = in.readDouble();
        int n = in.readInt();
        ring.clear();
        for (int i = 0; i < n; i++) ring.addLast(in.readBoolean());
    }
}
