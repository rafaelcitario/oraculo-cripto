package com.oraculo.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/** Rede neural minúscula (entrada -> tanh -> sigmoide) treinada online com SGD. Roda 100% no aparelho. */
public final class Mlp {
    private final int nIn, nH;
    private final double[] w1, b1, w2;
    private double b2;

    public Mlp(int nIn, int nH, long seed) {
        this.nIn = nIn;
        this.nH = nH;
        w1 = new double[nH * nIn];
        b1 = new double[nH];
        w2 = new double[nH];
        Random rnd = new Random(seed);
        double lim1 = Math.sqrt(6.0 / (nIn + nH));
        for (int i = 0; i < w1.length; i++) w1[i] = (rnd.nextDouble() * 2 - 1) * lim1;
        double lim2 = Math.sqrt(6.0 / (nH + 1));
        for (int j = 0; j < nH; j++) w2[j] = (rnd.nextDouble() * 2 - 1) * lim2;
    }

    /** Probabilidade estimada de o preço subir. */
    public double predict(double[] x) {
        double z2 = b2;
        for (int j = 0; j < nH; j++) {
            double z = b1[j];
            int base = j * nIn;
            for (int i = 0; i < nIn; i++) z += w1[base + i] * x[i];
            z2 += w2[j] * Math.tanh(z);
        }
        return sigmoid(z2);
    }

    /** Um passo de gradiente da entropia cruzada. y = 1 (subiu) ou 0 (desceu). */
    public void train(double[] x, int y, double lr, double l2) {
        double[] h = new double[nH];
        double z2 = b2;
        for (int j = 0; j < nH; j++) {
            double z = b1[j];
            int base = j * nIn;
            for (int i = 0; i < nIn; i++) z += w1[base + i] * x[i];
            h[j] = Math.tanh(z);
            z2 += w2[j] * h[j];
        }
        double d = sigmoid(z2) - y; // gradiente no logit
        for (int j = 0; j < nH; j++) {
            double dz = d * w2[j] * (1 - h[j] * h[j]);
            w2[j] -= lr * (d * h[j] + l2 * w2[j]);
            int base = j * nIn;
            for (int i = 0; i < nIn; i++) w1[base + i] -= lr * (dz * x[i] + l2 * w1[base + i]);
            b1[j] -= lr * dz;
        }
        b2 -= lr * d;
    }

    private static double sigmoid(double z) {
        if (z > 30) z = 30;
        if (z < -30) z = -30;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(nIn);
        out.writeInt(nH);
        for (double v : w1) out.writeDouble(v);
        for (double v : b1) out.writeDouble(v);
        for (double v : w2) out.writeDouble(v);
        out.writeDouble(b2);
    }

    public void load(DataInputStream in) throws IOException {
        int a = in.readInt(), b = in.readInt();
        if (a != nIn || b != nH) throw new IOException("formato de modelo incompatível");
        for (int i = 0; i < w1.length; i++) w1[i] = in.readDouble();
        for (int i = 0; i < b1.length; i++) b1[i] = in.readDouble();
        for (int i = 0; i < w2.length; i++) w2[i] = in.readDouble();
        b2 = in.readDouble();
    }
}
