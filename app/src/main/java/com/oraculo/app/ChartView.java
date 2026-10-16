package com.oraculo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.oraculo.core.Candle;
import com.oraculo.core.Oracle;
import com.oraculo.core.Prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gráfico de preço (1 min) com as previsões marcadas: verde = acertou, vermelho = errou, cinza = aguardando. */
public final class ChartView extends View {
    private static final int SLOTS = 120;
    private static final int FUTURE = 12;
    private static final int GREEN = 0xFF4CAF50, RED = 0xFFF44336, GRAY = 0xFF9AA4B2, ACCENT = 0xFF4FC3F7;

    private List<Candle> candles = new ArrayList<>();
    private List<Prediction> preds = new ArrayList<>();
    private double live;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public ChartView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        line.setColor(ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1f);
        txt.setColor(GRAY);
        txt.setTextSize(10f * density);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(2.5f * density);
        dash.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0));
        dot.setStyle(Paint.Style.FILL);
    }

    public void setData(List<Candle> candles, List<Prediction> preds, double live) {
        this.candles = candles;
        this.preds = preds;
        this.live = live;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float padL = 6 * density, padR = 6 * density, padT = 16 * density, padB = 6 * density;
        float plotW = w - padL - padR, plotH = h - padT - padB;
        int n = Math.min(candles.size(), SLOTS);
        if (n < 2) {
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText("Aguardando dados…", w / 2, h / 2, txt);
            txt.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int start = candles.size() - n;

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int i = start; i < candles.size(); i++) {
            double v = candles.get(i).close;
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        if (live > 0) {
            mn = Math.min(mn, live);
            mx = Math.max(mx, live);
        }
        double pad = (mx - mn) * 0.12;
        if (pad <= 0) pad = mx * 0.001;
        mn -= pad;
        mx += pad;
        final double fmn = mn, fmx = mx;
        final float slotW = plotW / (SLOTS + FUTURE);
        final float fpadT = padT, fplotH = plotH, fpadL = padL;

        for (int g = 0; g <= 3; g++) {
            float y = padT + plotH * g / 3f;
            cv.drawLine(padL, y, w - padR, y, grid);
        }
        cv.drawText(Fmt.price(mx), padL, padT - 4 * density, txt);
        cv.drawText(Fmt.price(mn), padL, h - 2, txt);

        Path path = new Path();
        Map<Long, Integer> idxByTime = new HashMap<>();
        for (int k = 0; k < n; k++) {
            Candle c = candles.get(start + k);
            idxByTime.put(c.openTime, k);
            float x = fpadL + (SLOTS - n + k + 0.5f) * slotW;
            float y = yOf(c.close, fmn, fmx, fpadT, fplotH);
            if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        float lastX = fpadL + (SLOTS - 1 + 0.5f) * slotW;
        if (live > 0) path.lineTo(lastX + slotW, yOf(live, fmn, fmx, fpadT, fplotH));
        cv.drawPath(path, line);

        Prediction pending = null;
        float pendX = 0, pendY = 0;
        for (Prediction p : preds) {
            if (p.voided) continue;
            Integer k = idxByTime.get(p.madeAt);
            if (k == null) continue;
            float x = fpadL + (SLOTS - n + k + 0.5f) * slotW;
            float y = yOf(p.price0, fmn, fmx, fpadT, fplotH);
            if (!p.resolved) {
                dot.setColor(GRAY);
                cv.drawCircle(x, y, 3.5f * density, dot);
                pending = p;
                pendX = x;
                pendY = y;
            } else {
                dot.setColor(p.hit ? GREEN : RED);
                cv.drawCircle(x, y, 3f * density, dot);
            }
        }

        if (pending != null) {
            boolean up = pending.predictsUp();
            float dy = plotH * 0.10f * (0.4f + (float) pending.confidence());
            float endX = Math.min(w - padR, pendX + Oracle.HORIZON_MIN * slotW);
            float endY = pendY + (up ? -dy : dy);
            dash.setColor(up ? GREEN : RED);
            cv.drawLine(pendX, pendY, endX, endY, dash);
            dot.setColor(up ? GREEN : RED);
            cv.drawCircle(endX, endY, 4f * density, dot);
            txt.setColor(up ? GREEN : RED);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("5 min " + (up ? "▲" : "▼"), w - padR, Math.max(padT + 10 * density, endY - 8 * density), txt);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(GRAY);
        }
    }

    private static float yOf(double v, double mn, double mx, float padT, float plotH) {
        return padT + (float) ((mx - v) / (mx - mn)) * plotH;
    }
}
