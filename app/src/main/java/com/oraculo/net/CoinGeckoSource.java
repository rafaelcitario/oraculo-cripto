package com.oraculo.net;

import com.oraculo.app.Session;
import com.oraculo.core.Candidate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lista as 100 maiores moedas por valor de mercado no CoinGecko (API pública, sem chave). */
public final class CoinGeckoSource implements Session.MarketSource {
    private static final String URL = "https://api.coingecko.com/api/v3/coins/markets"
            + "?vs_currency=usd&order=market_cap_desc&per_page=100&page=1&sparkline=false"
            + "&price_change_percentage=1h,24h,7d";

    @Override
    public List<Candidate> markets() throws IOException {
        return parse(Http.get(URL));
    }

    public static List<Candidate> parse(String json) throws IOException {
        try {
            JSONArray arr = new JSONArray(json);
            List<Candidate> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Candidate c = new Candidate();
                c.id = o.optString("id", "");
                c.symbol = o.optString("symbol", "").toUpperCase(Locale.ROOT);
                c.name = o.optString("name", c.symbol);
                c.price = o.optDouble("current_price", 0);
                c.marketCap = o.optDouble("market_cap", 0);
                c.volume24h = o.optDouble("total_volume", 0);
                c.high24h = o.optDouble("high_24h", 0);
                c.low24h = o.optDouble("low_24h", 0);
                c.ch1h = o.optDouble("price_change_percentage_1h_in_currency", 0);
                c.ch24h = o.optDouble("price_change_percentage_24h_in_currency",
                        o.optDouble("price_change_percentage_24h", 0));
                c.ch7d = o.optDouble("price_change_percentage_7d_in_currency", 0);
                if (!c.id.isEmpty() && !c.symbol.isEmpty() && c.price > 0) out.add(c);
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("resposta inesperada do CoinGecko", e);
        }
    }
}
