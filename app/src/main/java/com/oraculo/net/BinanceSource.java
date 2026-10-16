package com.oraculo.net;

import com.oraculo.app.Session;
import com.oraculo.core.Candle;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Velas de 1 minuto da API pública de mercado da Binance (somente leitura, sem chave, sem conta).
 * Tenta o endereço principal e, se falhar, o espelho de dados públicos.
 */
public final class BinanceSource implements Session.PriceSource {
    private static final String[] HOSTS = {"https://api.binance.com", "https://data-api.binance.vision"};
    private volatile int preferred = 0;

    private String get(String path) throws IOException {
        IOException last = null;
        for (int k = 0; k < HOSTS.length; k++) {
            int idx = (preferred + k) % HOSTS.length;
            try {
                String body = Http.get(HOSTS[idx] + path);
                preferred = idx;
                return body;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("sem acesso à fonte de preços");
    }

    @Override
    public Set<String> tradableBases() throws IOException {
        try {
            JSONArray arr = new JSONArray(get("/api/v3/ticker/price"));
            Set<String> out = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.getJSONObject(i).optString("symbol", "");
                if (s.endsWith("USDT") && s.length() > 4) out.add(s.substring(0, s.length() - 4));
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("resposta inesperada da Binance", e);
        }
    }

    @Override
    public List<Candle> klines(String symbol, int limit) throws IOException {
        return parseKlines(get("/api/v3/klines?symbol=" + symbol + "&interval=1m&limit=" + limit));
    }

    public static List<Candle> parseKlines(String json) throws IOException {
        try {
            JSONArray arr = new JSONArray(json);
            List<Candle> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONArray k = arr.getJSONArray(i);
                out.add(new Candle(
                        k.getLong(0),
                        Double.parseDouble(k.getString(1)),
                        Double.parseDouble(k.getString(2)),
                        Double.parseDouble(k.getString(3)),
                        Double.parseDouble(k.getString(4)),
                        Double.parseDouble(k.getString(5))));
            }
            return out; // a última vela é a que ainda está em andamento
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("velas em formato inesperado", e);
        }
    }
}
