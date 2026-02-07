package pl.kejlo.mzutv2.wear.watchface;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.wear.watchface.CanvasType;
import androidx.wear.watchface.Renderer;
import androidx.wear.watchface.WatchState;
import androidx.wear.watchface.style.CurrentUserStyleRepository;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.sync.WearSnapshotStore;
import pl.kejlo.mzutv2.wear.util.WearScheduleUtils;

public class MzutWatchFaceRenderer extends Renderer.CanvasRenderer {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE dd.MM", Locale.getDefault());

    private final Paint timePaint = new Paint();
    private final Paint datePaint = new Paint();
    private final Paint infoPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final Paint etaPaint = new Paint();
    private final Paint ringPaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Paint glowPaint = new Paint();
    private final Paint cardPaint = new Paint();
    private final Paint cardStrokePaint = new Paint();
    private final Paint cardLabelPaint = new Paint();
    private final Paint cardTitlePaint = new Paint();
    private final Paint cardMetaPaint = new Paint();
    private final Paint cardEtaPaint = new Paint();
    private final Context context;
    private final WatchState watchState;

    public MzutWatchFaceRenderer(
            @NonNull Context context,
            @NonNull SurfaceHolder holder,
            @NonNull WatchState watchState,
            @NonNull CurrentUserStyleRepository styleRepository) {
        super(holder, styleRepository, watchState, CanvasType.HARDWARE, 60_000L);
        this.context = context;
        this.watchState = watchState;

        timePaint.setColor(Color.WHITE);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setTextSize(56f);
        timePaint.setAntiAlias(true);
        timePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        datePaint.setColor(0xFFB0B7C3);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setTextSize(16f);
        datePaint.setAntiAlias(true);

        infoPaint.setColor(0xFF8F96A3);
        infoPaint.setTextAlign(Paint.Align.CENTER);
        infoPaint.setTextSize(14f);
        infoPaint.setAntiAlias(true);

        labelPaint.setColor(0xFF8F96A3);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(12f);
        labelPaint.setAntiAlias(true);

        etaPaint.setColor(Color.WHITE);
        etaPaint.setTextAlign(Paint.Align.CENTER);
        etaPaint.setTextSize(16f);
        etaPaint.setAntiAlias(true);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(4f);
        ringPaint.setAntiAlias(true);

        glowPaint.setAntiAlias(true);
        cardPaint.setAntiAlias(true);
        cardStrokePaint.setAntiAlias(true);
        cardStrokePaint.setStyle(Paint.Style.STROKE);
        cardStrokePaint.setStrokeWidth(2f);

        cardLabelPaint.setAntiAlias(true);
        cardLabelPaint.setTextAlign(Paint.Align.LEFT);
        cardLabelPaint.setColor(0xFF8F96A3);

        cardTitlePaint.setAntiAlias(true);
        cardTitlePaint.setTextAlign(Paint.Align.LEFT);
        cardTitlePaint.setColor(Color.WHITE);
        cardTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        cardMetaPaint.setAntiAlias(true);
        cardMetaPaint.setTextAlign(Paint.Align.LEFT);
        cardMetaPaint.setColor(0xFFB0B7C3);

        cardEtaPaint.setAntiAlias(true);
        cardEtaPaint.setTextAlign(Paint.Align.LEFT);
        cardEtaPaint.setColor(Color.WHITE);
    }

    @Override
    public void render(@NonNull Canvas canvas, @NonNull Rect bounds, @NonNull ZonedDateTime time) {
        boolean ambient = watchState.isAmbient().getValue();

        int base = Math.min(bounds.width(), bounds.height());
        float cx = bounds.centerX();
        float cy = bounds.centerY();

        WearPlanSnapshot snap = WearSnapshotStore.load(context);
        WearScheduleUtils.NextEventInfo next = WearScheduleUtils.findNextEvent(snap, time);
        int accent = (next != null && next.event != null && next.event.color != 0)
                ? next.event.color
                : 0xFF4F8DFF;
        int bg = snap != null && snap.colorBg != 0 ? snap.colorBg : 0xFF0B1020;
        int card = snap != null && snap.colorCard != 0 ? snap.colorCard : 0xFF111827;
        int text = snap != null && snap.colorText != 0 ? snap.colorText : Color.WHITE;
        int muted = snap != null && snap.colorMuted != 0 ? snap.colorMuted : 0xFFB0B7C3;
        int subtle = snap != null && snap.colorSubtle != 0 ? snap.colorSubtle : 0xFF8F96A3;

        if (ambient) {
            canvas.drawColor(Color.BLACK);
            timePaint.setColor(Color.WHITE);
            datePaint.setColor(0xFFB0B7C3);
            infoPaint.setColor(0xFF8F96A3);
            labelPaint.setColor(0xFF8F96A3);
            etaPaint.setColor(Color.WHITE);
        } else {
            int bg1 = bg;
            int bg2 = ColorUtils.blendARGB(bg, accent, 0.25f);
            RadialGradient grad = new RadialGradient(
                    cx, cy, base * 0.72f, bg2, bg1, Shader.TileMode.CLAMP);
            bgPaint.setShader(grad);
            canvas.drawRect(bounds, bgPaint);

            LinearGradient sheen = new LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    new int[] { 0x22FFFFFF, 0x00FFFFFF, 0x00000000 },
                    new float[] { 0f, 0.4f, 1f },
                    Shader.TileMode.CLAMP);
            Paint sheenPaint = new Paint();
            sheenPaint.setShader(sheen);
            canvas.drawRect(bounds, sheenPaint);

            int glowColor = 0x55000000 | (accent & 0x00FFFFFF);
            RadialGradient glow = new RadialGradient(
                    cx, cy - base * 0.18f, base * 0.55f,
                    new int[] { glowColor, 0x00000000 },
                    new float[] { 0f, 1f },
                    Shader.TileMode.CLAMP);
            glowPaint.setShader(glow);
            canvas.drawRect(bounds, glowPaint);

            timePaint.setColor(text);
            datePaint.setColor(muted);
            infoPaint.setColor(subtle);
            labelPaint.setColor(subtle);
            etaPaint.setColor(text);
        }

        String timeStr = TIME_FMT.format(time);
        String dateStr = DATE_FMT.format(time);

        float timeSize = base * 0.26f;
        float dateSize = base * 0.08f;
        float infoSize = base * 0.065f;
        float etaSize = base * 0.075f;
        float cardLabelSize = base * 0.05f;
        float cardTitleSize = base * 0.075f;
        float cardMetaSize = base * 0.05f;
        float cardEtaSize = base * 0.065f;

        timePaint.setTextSize(timeSize);
        datePaint.setTextSize(dateSize);
        infoPaint.setTextSize(infoSize);
        etaPaint.setTextSize(etaSize);
        cardLabelPaint.setTextSize(cardLabelSize);
        cardTitlePaint.setTextSize(cardTitleSize);
        cardMetaPaint.setTextSize(cardMetaSize);
        cardEtaPaint.setTextSize(cardEtaSize);

        if (ambient) {
            timePaint.clearShadowLayer();
        } else {
            timePaint.setShadowLayer(base * 0.02f, 0f, base * 0.01f, 0x66000000);
        }

        if (!ambient) {
            ringPaint.setColor(accent);
            ringPaint.setAlpha(160);
            float ringPad = base * 0.06f;
            canvas.drawOval(
                    bounds.left + ringPad,
                    bounds.top + ringPad,
                    bounds.right - ringPad,
                    bounds.bottom - ringPad,
                    ringPaint);
        }

        canvas.drawText(timeStr, cx, cy - base * 0.05f, timePaint);
        canvas.drawText(dateStr, cx, cy + base * 0.12f, datePaint);

        if (snap != null && snap.loginRequired) {
            String info = context.getString(pl.kejlo.mzutv2.wear.R.string.watch_face_login_required);
            canvas.drawText(info, cx, cy + base * 0.28f, infoPaint);
            return;
        }

        if (next == null || next.event == null) {
            String info = context.getString(pl.kejlo.mzutv2.wear.R.string.watch_face_no_next);
            canvas.drawText(info, cx, cy + base * 0.30f, infoPaint);
            return;
        }

        String title = WearScheduleUtils.ellipsize(next.event.title, 20);
        String eta = WearScheduleUtils.formatEta(time, next.start);
        String timeRange = next.timeRange != null ? next.timeRange : "";

        if (ambient) {
            canvas.drawText(title, cx, cy + base * 0.30f, infoPaint);
            canvas.drawText(eta, cx, cy + base * 0.40f, etaPaint);
            if (timeRange != null && !timeRange.isEmpty()) {
                canvas.drawText(timeRange, cx, cy + base * 0.49f, datePaint);
            }
            return;
        }

        cardLabelPaint.setColor(accent);
        cardTitlePaint.setColor(text);
        cardMetaPaint.setColor(muted);
        cardEtaPaint.setColor(text);

        float cardWidth = base * 0.82f;
        float cardHeight = base * 0.26f;
        float cardLeft = cx - cardWidth / 2f;
        float cardTop = cy + base * 0.20f;
        RectF cardRect = new RectF(
                cardLeft,
                cardTop,
                cardLeft + cardWidth,
                cardTop + cardHeight);
        float radius = base * 0.06f;
        cardPaint.setColor(ColorUtils.setAlphaComponent(card, 0xCC));
        cardStrokePaint.setColor(accent);
        cardStrokePaint.setAlpha(140);
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint);
        canvas.drawRoundRect(cardRect, radius, radius, cardStrokePaint);

        float pad = base * 0.04f;
        float barWidth = base * 0.02f;
        Paint accentFill = ringPaint;
        accentFill.setStyle(Paint.Style.FILL);
        accentFill.setColor(accent);
        accentFill.setAlpha(200);
        RectF bar = new RectF(
                cardRect.left + pad,
                cardRect.top + pad,
                cardRect.left + pad + barWidth,
                cardRect.bottom - pad);
        canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, accentFill);
        ringPaint.setStyle(Paint.Style.STROKE);

        cardLabelPaint.setColor(accent);
        float textX = bar.right + pad;
        float y = cardRect.top + pad + cardLabelPaint.getTextSize();
        canvas.drawText(context.getString(pl.kejlo.mzutv2.wear.R.string.watch_face_next_label),
                textX, y, cardLabelPaint);
        y += cardTitlePaint.getTextSize() + base * 0.01f;
        canvas.drawText(title, textX, y, cardTitlePaint);
        y += cardEtaPaint.getTextSize() + base * 0.01f;
        canvas.drawText(eta, textX, y, cardEtaPaint);
        if (timeRange != null && !timeRange.isEmpty()) {
            y += cardMetaPaint.getTextSize() + base * 0.005f;
            canvas.drawText(timeRange, textX, y, cardMetaPaint);
        }
    }

    @Override
    public void renderHighlightLayer(@NonNull Canvas canvas, @NonNull Rect bounds,
            @NonNull ZonedDateTime time) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    }
}
