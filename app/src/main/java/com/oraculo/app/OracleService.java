package com.oraculo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.oraculo.R;
import com.oraculo.core.Candidate;
import com.oraculo.core.Stats;

/** Mantém o oráculo rodando com a tela apagada (serviço em primeiro plano, com notificação fixa). */
public class OracleService extends Service {
    private static final String CHANNEL = "oraculo";
    private static final int NOTIF_ID = 1;

    private Session session;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
            handler.postDelayed(this, 20_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        session = Sessions.get(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Oráculo em execução", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oraculo:sessao");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(12 * 60 * 60 * 1000L);
        session.start();
        handler.removeCallbacks(updater);
        handler.postDelayed(updater, 5_000);
        return START_STICKY;
    }

    /** Android 15+ limita serviços "dataSync" a ~6 h por dia: salvamos tudo e paramos com elegância. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        session.stop();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        session.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Candidate pick = session.pick();
        Stats st = session.oracle.stats;
        String text;
        if (pick != null && st.total > 0) {
            text = pick.symbol + " • acerto " + Fmt.pct(st.accuracy(), 1) + " em " + st.total
                    + " • carteira " + Fmt.signedPct(session.trader.pnl() / session.trader.startCash, 2);
        } else {
            text = session.status();
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Oráculo Cripto — simulação")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }
}
