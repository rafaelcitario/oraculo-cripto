package com.oraculo.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GET mínimo, sem bibliotecas externas. */
final class Http {
    private Http() {}

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", "OraculoCripto/1.0");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : read(in);
            if (code >= 400) {
                String host = new URL(url).getHost();
                throw new IOException("HTTP " + code + " em " + host + (code == 429 ? " (limite de requisições)" : ""));
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
