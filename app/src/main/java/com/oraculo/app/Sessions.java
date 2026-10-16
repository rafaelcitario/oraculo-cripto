package com.oraculo.app;

import android.content.Context;

import com.oraculo.net.BinanceSource;
import com.oraculo.net.CoinGeckoSource;

/** Uma única sessão por processo, compartilhada entre a tela e o serviço em segundo plano. */
public final class Sessions {
    private static Session instance;

    private Sessions() {}

    public static synchronized Session get(Context ctx) {
        if (instance == null) {
            Context app = ctx.getApplicationContext();
            instance = new Session(
                    app.getFilesDir(),
                    new PrefsStore(app.getSharedPreferences("oraculo", Context.MODE_PRIVATE)),
                    new CoinGeckoSource(),
                    new BinanceSource(),
                    System::currentTimeMillis);
        }
        return instance;
    }
}
