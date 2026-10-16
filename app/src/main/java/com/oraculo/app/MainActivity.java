package com.oraculo.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oraculo.core.Candidate;
import com.oraculo.core.Oracle;
import com.oraculo.core.PaperTrader;
import com.oraculo.core.Prediction;
import com.oraculo.core.Stats;

import java.util.List;

public class MainActivity extends Activity {
    private static final int BG = 0xFF0E1116, CARD = 0xFF171C24, TEXT = 0xFFE6EAF0, MUTED = 0xFF9AA4B2,
            GREEN = 0xFF4CAF50, RED = 0xFFF44336, ACCENT = 0xFF4FC3F7, AMBER = 0xFFFFB300;

    private Session session;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusTv, assetTv, reasonTv, predTv, timerTv, scoreTv, verdictTv, walletTv, rankTv;
    private ChartView chart;
    private Button toggleBtn, rescanBtn;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = Sessions.get(this);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    // ------------------------------------------------------------------ interface

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("Oráculo Cripto", 24, TEXT, true));
        root.addView(text("SIMULAÇÃO — nenhum dinheiro real, nenhuma ordem real", 12, AMBER, false));
        statusTv = text("Parado", 12, MUTED, false);
        statusTv.setPadding(0, dp(4), 0, dp(10));
        root.addView(statusTv);

        LinearLayout c1 = card(root);
        assetTv = text("—", 20, TEXT, true);
        reasonTv = text("", 12, MUTED, false);
        c1.addView(assetTv);
        c1.addView(reasonTv);
        chart = new ChartView(this);
        c1.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        LinearLayout c2 = card(root);
        c2.addView(text("Previsão para daqui a 5 minutos", 12, MUTED, false));
        predTv = text("Aguardando dados…", 22, TEXT, true);
        timerTv = text("", 12, MUTED, false);
        c2.addView(predTv);
        c2.addView(timerTv);

        LinearLayout c3 = card(root);
        c3.addView(text("Placar do oráculo (prêmio e punição)", 12, MUTED, false));
        scoreTv = text("", 14, TEXT, false);
        verdictTv = text("", 13, AMBER, true);
        c3.addView(scoreTv);
        c3.addView(verdictTv);

        LinearLayout c4 = card(root);
        c4.addView(text("Carteira simulada", 12, MUTED, false));
        walletTv = text("", 14, TEXT, false);
        c4.addView(walletTv);

        LinearLayout c5 = card(root);
        c5.addView(text("Candidatas de hoje (CoinGecko)", 12, MUTED, false));
        rankTv = text("", 13, TEXT, false);
        c5.addView(rankTv);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        toggleBtn = new Button(this);
        toggleBtn.setAllCaps(false);
        toggleBtn.setOnClickListener(v -> toggle());
        rescanBtn = new Button(this);
        rescanBtn.setAllCaps(false);
        rescanBtn.setText("Trocar de ativo");
        rescanBtn.setOnClickListener(v -> session.requestRescan());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(toggleBtn, lp);
        buttons.addView(rescanBtn, lp);
        root.addView(buttons);

        TextView foot = text("Prever o mercado de 5 minutos é muito difícil: o placar mostra a taxa de acerto "
                + "com margem de erro e compara com palpites bobos. Só acredite na vantagem quando o selo ficar verde.",
                11, MUTED, false);
        foot.setPadding(0, dp(14), 0, 0);
        root.addView(foot);

        setContentView(sv);
    }

    private void toggle() {
        Intent i = new Intent(this, OracleService.class);
        if (session.isRunning()) stopService(i); else startForegroundService(i);
    }

    // ------------------------------------------------------------------ atualização da tela

    private void render() {
        statusTv.setText(session.status());
        toggleBtn.setText(session.isRunning() ? "Parar" : "Iniciar oráculo");
        rescanBtn.setEnabled(session.isRunning() && session.isReady());

        Candidate pk = session.pick();
        double live = session.livePrice();
        if (pk == null) {
            assetTv.setText("Nenhum ativo escolhido ainda");
            reasonTv.setText("Toque em “Iniciar oráculo” para escanear o mercado e escolher o ativo de hoje.");
        } else {
            assetTv.setText(pk.symbol + " • " + pk.name + (live > 0 ? "   US$ " + Fmt.price(live) : ""));
            reasonTv.setText(pk.reasons.isEmpty() ? "" : "Escolhida porque: " + join(pk.reasons));
        }

        Oracle oracle = session.oracle;
        List<Prediction> recent = oracle.recentPredictions();
        chart.setData(session.seriesSnapshot(), recent, live);

        Prediction pr = oracle.latest();
        if (pr == null) {
            predTv.setText(session.isReady() ? "Aguardando a próxima vela…" : "Aguardando dados…");
            predTv.setTextColor(TEXT);
            timerTv.setText("");
        } else {
            boolean up = pr.predictsUp();
            double prob = up ? pr.pUp : 1 - pr.pUp;
            predTv.setText((up ? "▲ ALTA  " : "▼ QUEDA  ") + Fmt.pct(prob, 0));
            predTv.setTextColor(up ? GREEN : RED);
            long left = pr.targetTime + 60_000L - System.currentTimeMillis();
            String conviction = pr.confidence() >= 0.2 ? "convicção alta" : "convicção baixa (quase moeda ao ar)";
            timerTv.setText("Resultado em " + Fmt.clock(left) + " • " + conviction);
        }

        Stats st = oracle.stats;
        StringBuilder sb = new StringBuilder();
        if (st.total == 0) {
            sb.append("Ainda sem previsões resolvidas. A primeira resolve em ~5 minutos.\n");
        } else {
            double[] ci = st.wilson();
            sb.append("Acertos: ").append(st.hits).append(" de ").append(st.total)
                    .append("  (").append(Fmt.pct(st.accuracy(), 1)).append(")\n");
            sb.append("Margem de erro 95%: ").append(Fmt.pct(ci[0], 1)).append(" a ").append(Fmt.pct(ci[1], 1)).append("\n");
            sb.append("Últimas ").append(st.rollingN()).append(": ").append(Fmt.pct(st.rollingAccuracy(), 0)).append("\n");
            if (st.hiTotal > 0)
                sb.append("Com convicção alta: ").append(Fmt.pct(st.hiAccuracy(), 1)).append(" (").append(st.hiTotal).append(")\n");
            sb.append("Palpite bobo “repetir a tendência”: ").append(Fmt.pct(st.momentumAccuracy(), 1)).append("\n");
            sb.append("Palpite bobo “sempre alta”: ").append(Fmt.pct(st.alwaysUpAccuracy(), 1)).append("\n");
            sb.append(String.format(Fmt.BR, "Recompensa acumulada: %+.1f", st.reward)).append("\n");
        }
        Stats ws = oracle.warmStats;
        if (ws.total > 0)
            sb.append("Pré-treino (histórico, avaliado às cegas): ").append(Fmt.pct(ws.accuracy(), 1)).append(" em ").append(ws.total);
        scoreTv.setText(sb.toString().trim());

        if (st.total < 500) {
            verdictTv.setText("Selo: coletando evidência (" + st.total + "/500 previsões)");
            verdictTv.setTextColor(AMBER);
        } else if (st.significantEdge()) {
            verdictTv.setText("Selo: VANTAGEM ESTATÍSTICA detectada — ainda não cobre as taxas, veja a carteira");
            verdictTv.setTextColor(GREEN);
        } else {
            verdictTv.setText("Selo: sem vantagem comprovada (indistinguível de sorte)");
            verdictTv.setTextColor(RED);
        }

        PaperTrader t = session.trader;
        double ret = t.pnl() / t.startCash;
        StringBuilder wb = new StringBuilder();
        wb.append(Fmt.money(t.equity())).append("  (").append(Fmt.signedPct(ret, 2)).append(")\n");
        wb.append("Operações: ").append(t.tradeCount());
        if (t.tradeCount() > 0) wb.append("  •  vencedoras: ").append(Fmt.pct(t.winRate(), 0));
        wb.append("\nSó comprar e segurar renderia: ").append(Fmt.signedPct(t.buyAndHoldPct(), 2)).append(" (sem taxas)");
        wb.append("\nPosição agora: ").append(t.isOpen() ? "comprado (simulado)" : "fora");
        wb.append("\nTaxas simuladas: 0,10% por lado + 0,02% de derrapagem");
        walletTv.setText(wb.toString());

        List<Candidate> rk = session.ranking();
        StringBuilder rb = new StringBuilder();
        for (int i = 0; i < Math.min(5, rk.size()); i++) {
            Candidate c = rk.get(i);
            rb.append(i + 1).append(". ").append(c.symbol).append("  nota ").append(Math.round(c.score));
            if (pk != null && c.id.equals(pk.id)) rb.append("  ← em uso");
            rb.append("\n   ").append(join(c.reasons)).append("\n");
        }
        rankTv.setText(rb.length() == 0 ? "—" : rb.toString().trim());
    }

    // ------------------------------------------------------------------ utilidades

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        parent.addView(c, lp);
        return c;
    }
}
