package pl.kejlo.zutnik;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SemesterPdfExportHelper {

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final float MARGIN = 42f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (2f * MARGIN);
    private static final float CONTENT_BOTTOM = 798f;

    private static final int COLOR_INK = Color.rgb(23, 32, 39);
    private static final int COLOR_MUTED = Color.rgb(93, 107, 114);
    private static final int COLOR_ACCENT = Color.rgb(26, 143, 132);
    private static final int COLOR_SURFACE = Color.rgb(242, 246, 246);
    private static final int COLOR_LINE = Color.rgb(219, 228, 230);
    private static final int COLOR_DANGER = Color.rgb(181, 71, 71);
    private static final int COLOR_BLUE = Color.rgb(75, 111, 164);
    private static final int COLOR_PURPLE = Color.rgb(116, 85, 151);
    private static final int COLOR_GREEN = Color.rgb(55, 133, 99);

    private SemesterPdfExportHelper() {
    }

    @NonNull
    public static PdfResult build(
            @NonNull Context context,
            @NonNull List<PlanCalendarExportHelper.CalendarExportEvent> sourceEvents) throws Exception {
        List<PlanCalendarExportHelper.CalendarExportEvent> events = new ArrayList<>();
        for (PlanCalendarExportHelper.CalendarExportEvent item : sourceEvents) {
            if (item != null && item.date != null && item.event != null) {
                events.add(item);
            }
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Semester export has no events");
        }

        LocalDate start = events.get(0).date;
        LocalDate end = events.get(events.size() - 1).date;
        Map<LocalDate, List<PlanCalendarExportHelper.CalendarExportEvent>> days = new LinkedHashMap<>();
        Set<String> subjects = new LinkedHashSet<>();
        for (PlanCalendarExportHelper.CalendarExportEvent item : events) {
            days.computeIfAbsent(item.date, ignored -> new ArrayList<>()).add(item);
            String title = clean(item.event.title);
            if (!title.isEmpty()) {
                subjects.add(title);
            }
        }

        Locale locale = Locale.getDefault();
        String range = formatDate(start, locale) + " - " + formatDate(end, locale);
        String fileName = "zutnik-plan-semestru-" + start + "-" + end + ".pdf";

        PdfDocument document = new PdfDocument();
        PdfWriter writer = new PdfWriter(context, document, range);
        try {
            writer.startFirstPage(events.size(), days.size(), subjects.size());
            for (Map.Entry<LocalDate, List<PlanCalendarExportHelper.CalendarExportEvent>> entry : days.entrySet()) {
                writer.drawDay(entry.getKey(), entry.getValue());
            }
            writer.finish();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.writeTo(output);
            return new PdfResult(output.toByteArray(), fileName, events.size());
        } finally {
            document.close();
        }
    }

    @NonNull
    public static Uri writeShareCacheFile(
            @NonNull Context context,
            @NonNull byte[] content,
            @NonNull String fileName) throws Exception {
        File directory = new File(context.getCacheDir(), "shared_reports");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create shared reports directory");
        }
        File output = new File(directory, sanitizeFileName(fileName));
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(content);
            stream.flush();
        }
        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                output);
    }

    public static final class PdfResult {
        public final byte[] content;
        public final String fileName;
        public final int eventCount;

        PdfResult(byte[] content, String fileName, int eventCount) {
            this.content = content;
            this.fileName = fileName;
            this.eventCount = eventCount;
        }
    }

    private static final class PdfWriter {
        private final Context context;
        private final PdfDocument document;
        private final String range;
        private final Locale locale;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);

        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber;
        private float y;

        PdfWriter(Context context, PdfDocument document, String range) {
            this.context = context;
            this.document = document;
            this.range = range;
            this.locale = Locale.getDefault();
        }

        void startFirstPage(int eventCount, int dayCount, int subjectCount) {
            startPage(false);
            setPaint(COLOR_ACCENT, 10f, medium);
            canvas.drawText("ZUTNIK", MARGIN, 48f, paint);
            drawLine(58f, COLOR_LINE, 1f);

            setPaint(COLOR_INK, 28f, bold);
            canvas.drawText(context.getString(R.string.calendar_export_pdf_document_title), MARGIN, 98f, paint);

            String studyLabel = resolveStudyLabel();
            if (!studyLabel.isEmpty()) {
                setPaint(COLOR_MUTED, 10f, regular);
                drawSingleLineEllipsized(studyLabel, MARGIN, 120f, CONTENT_WIDTH);
            }

            setPaint(COLOR_INK, 12f, medium);
            canvas.drawText(range, MARGIN, 145f, paint);

            float gap = 10f;
            float metricWidth = (CONTENT_WIDTH - 2f * gap) / 3f;
            drawMetric(MARGIN, 164f, metricWidth, String.valueOf(eventCount),
                    context.getString(R.string.calendar_export_pdf_metric_events));
            drawMetric(MARGIN + metricWidth + gap, 164f, metricWidth, String.valueOf(dayCount),
                    context.getString(R.string.calendar_export_pdf_metric_days));
            drawMetric(MARGIN + 2f * (metricWidth + gap), 164f, metricWidth, String.valueOf(subjectCount),
                    context.getString(R.string.calendar_export_pdf_metric_subjects));

            setPaint(COLOR_INK, 16f, bold);
            canvas.drawText(context.getString(R.string.calendar_export_pdf_schedule_title), MARGIN, 252f, paint);
            setPaint(COLOR_MUTED, 9f, regular);
            String generated = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", locale));
            canvas.drawText(context.getString(R.string.calendar_export_pdf_generated, generated), MARGIN, 270f, paint);
            y = 292f;
        }

        void drawDay(
                LocalDate date,
                List<PlanCalendarExportHelper.CalendarExportEvent> events) {
            if (events == null || events.isEmpty()) {
                return;
            }
            float firstHeight = measureEventHeight(events.get(0).event);
            ensureSpace(31f + firstHeight);
            drawDayHeader(date, false);

            for (PlanCalendarExportHelper.CalendarExportEvent item : events) {
                float eventHeight = measureEventHeight(item.event);
                if (y + eventHeight > CONTENT_BOTTOM) {
                    startPage(true);
                    drawDayHeader(date, true);
                }
                drawEvent(item.event, eventHeight);
            }
            y += 9f;
        }

        void finish() {
            finishCurrentPage();
        }

        private void startPage(boolean continuation) {
            finishCurrentPage();
            pageNumber++;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH,
                    PAGE_HEIGHT,
                    pageNumber)
                    .create();
            page = document.startPage(info);
            canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            if (continuation) {
                setPaint(COLOR_ACCENT, 9f, medium);
                canvas.drawText("ZUTNIK", MARGIN, 42f, paint);
                setPaint(COLOR_MUTED, 9f, regular);
                drawSingleLineEllipsized(range, PAGE_WIDTH - MARGIN - 190f, 42f, 190f);
                drawLine(54f, COLOR_LINE, 1f);
                y = 72f;
            }
        }

        private void finishCurrentPage() {
            if (page == null) {
                return;
            }
            drawLine(813f, COLOR_LINE, 1f);
            setPaint(COLOR_MUTED, 8f, regular);
            canvas.drawText(context.getString(R.string.app_name), MARGIN, 829f, paint);
            String pageLabel = context.getString(R.string.calendar_export_pdf_page, pageNumber);
            canvas.drawText(
                    pageLabel,
                    PAGE_WIDTH - MARGIN - paint.measureText(pageLabel),
                    829f,
                    paint);
            document.finishPage(page);
            page = null;
            canvas = null;
        }

        private void ensureSpace(float required) {
            if (y + required <= CONTENT_BOTTOM) {
                return;
            }
            startPage(true);
        }

        private void drawMetric(float x, float top, float width, String value, String label) {
            paint.setColor(COLOR_SURFACE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(x, top, x + width, top + 58f), 7f, 7f, paint);
            setPaint(COLOR_INK, 18f, bold);
            canvas.drawText(value, x + 13f, top + 25f, paint);
            setPaint(COLOR_MUTED, 8f, medium);
            drawSingleLineEllipsized(label, x + 13f, top + 44f, width - 26f);
        }

        private void drawDayHeader(LocalDate date, boolean continuation) {
            paint.setColor(COLOR_SURFACE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(MARGIN, y, PAGE_WIDTH - MARGIN, y + 27f), 5f, 5f, paint);
            setPaint(COLOR_INK, 10.5f, bold);
            String dateLabel = capitalize(date.format(
                    DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)));
            if (continuation) {
                dateLabel += " - " + context.getString(R.string.calendar_export_pdf_continued);
            }
            drawSingleLineEllipsized(dateLabel, MARGIN + 11f, y + 18f, CONTENT_WIDTH - 22f);
            y += 33f;
        }

        private void drawEvent(PlanRepository.PlanEventUi event, float height) {
            int accent = resolveEventColor(event);
            paint.setColor(accent);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(MARGIN, y + 5f, MARGIN + 3f, y + height - 5f), 2f, 2f, paint);

            float timeX = MARGIN + 12f;
            float contentX = MARGIN + 83f;
            float contentWidth = PAGE_WIDTH - MARGIN - contentX;

            setPaint(COLOR_INK, 9.5f, medium);
            canvas.drawText(formatTime(event), timeX, y + 19f, paint);

            setPaint(COLOR_INK, 10.5f, bold);
            List<String> titleLines = wrap(cleanOrDefault(
                    event.title,
                    context.getString(R.string.plan_export_ics_default_title)), contentWidth, 2);
            float titleY = y + 18f;
            for (String line : titleLines) {
                canvas.drawText(line, contentX, titleY, paint);
                titleY += 13f;
            }

            String meta = buildMeta(event);
            if (!meta.isEmpty()) {
                setPaint(COLOR_MUTED, 8.5f, regular);
                List<String> metaLines = wrap(meta, contentWidth, 2);
                float metaY = titleY + 2f;
                for (String line : metaLines) {
                    canvas.drawText(line, contentX, metaY, paint);
                    metaY += 11f;
                }
            }

            paint.setColor(COLOR_LINE);
            paint.setStrokeWidth(0.8f);
            canvas.drawLine(MARGIN + 10f, y + height, PAGE_WIDTH - MARGIN, y + height, paint);
            y += height;
        }

        private float measureEventHeight(PlanRepository.PlanEventUi event) {
            float contentWidth = PAGE_WIDTH - (MARGIN + 83f) - MARGIN;
            setPaint(COLOR_INK, 10.5f, bold);
            int titleLines = wrap(cleanOrDefault(event.title, "-"), contentWidth, 2).size();
            setPaint(COLOR_MUTED, 8.5f, regular);
            int metaLines = buildMeta(event).isEmpty() ? 0 : wrap(buildMeta(event), contentWidth, 2).size();
            return Math.max(48f, 19f + titleLines * 13f + metaLines * 11f + 5f);
        }

        private String buildMeta(PlanRepository.PlanEventUi event) {
            List<String> parts = new ArrayList<>();
            addPart(parts, localizedTypeLabel(event.typeLabel));
            String room = clean(event.room);
            if (!room.isEmpty()) {
                addPart(parts, context.getString(R.string.plan_widget_room_format, room));
            }
            String group = clean(event.group);
            if (!group.isEmpty()) {
                addPart(parts, context.getString(R.string.plan_widget_group_format, group));
            }
            addPart(parts, clean(event.teacher));
            if (event.hasCustomOverlay) {
                addPart(parts, clean(event.customOverlayLabel));
            } else if (event.isCustomEvent) {
                addPart(parts, context.getString(R.string.plan_export_ics_custom_event));
            }
            return String.join(" | ", parts);
        }

        private String localizedTypeLabel(String source) {
            String label = clean(source);
            String normalized = label.toLowerCase(Locale.ROOT);
            if (normalized.contains("lecture") || normalized.contains("wykład")
                    || normalized.contains("wyklad")) {
                return context.getString(R.string.calendar_export_pdf_type_lecture);
            }
            if (normalized.contains("laboratory") || normalized.contains("laboratorium")
                    || normalized.equals("lab")) {
                return context.getString(R.string.calendar_export_pdf_type_laboratory);
            }
            if (normalized.contains("auditory") || normalized.contains("exercise")
                    || normalized.contains("ćwic") || normalized.contains("cwicz")) {
                return context.getString(R.string.calendar_export_pdf_type_classes);
            }
            if (normalized.contains("exam") || normalized.contains("egzamin")) {
                return context.getString(R.string.calendar_export_pdf_type_exam);
            }
            return label;
        }

        private int resolveEventColor(PlanRepository.PlanEventUi event) {
            String type = (clean(event.typeLabel) + " " + clean(event.typeClass)).toLowerCase(locale);
            String title = clean(event.title).toLowerCase(locale);
            if (event.isCustomEvent || event.hasCustomOverlay
                    || title.contains("odwoł") || title.contains("cancel")) {
                return COLOR_DANGER;
            }
            if (type.contains("lab")) {
                return COLOR_PURPLE;
            }
            if (type.contains("wyk") || type.contains("lecture")) {
                return COLOR_BLUE;
            }
            if (type.contains("ćw") || type.contains("cw") || type.contains("exercise")) {
                return COLOR_GREEN;
            }
            return COLOR_ACCENT;
        }

        private String formatTime(PlanRepository.PlanEventUi event) {
            String start = clean(event.startStr);
            String end = clean(event.endStr);
            if (start.isEmpty()) {
                start = minutesToTime(event.startMin);
            }
            if (end.isEmpty()) {
                end = minutesToTime(event.endMin);
            }
            return start + "-" + end;
        }

        private void drawLine(float lineY, int color, float width) {
            paint.setColor(color);
            paint.setStrokeWidth(width);
            canvas.drawLine(MARGIN, lineY, PAGE_WIDTH - MARGIN, lineY, paint);
        }

        private void setPaint(int color, float size, Typeface typeface) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(typeface);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(1f);
        }

        private List<String> wrap(String text, float width, int maxLines) {
            List<String> lines = new ArrayList<>();
            String normalized = clean(text);
            if (normalized.isEmpty()) {
                return lines;
            }

            String[] words = normalized.split(" ");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (paint.measureText(candidate) <= width) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    if (lines.size() == maxLines) {
                        break;
                    }
                }
                if (paint.measureText(word) <= width) {
                    current.append(word);
                } else {
                    current.append(ellipsize(word, width));
                }
            }
            if (lines.size() < maxLines && current.length() > 0) {
                lines.add(current.toString());
            }
            if (lines.isEmpty()) {
                lines.add(ellipsize(normalized, width));
            }
            if (lines.size() == maxLines) {
                int consumed = 0;
                for (String line : lines) {
                    consumed += line.length();
                }
                if (consumed < normalized.length() - (lines.size() - 1)) {
                    int last = lines.size() - 1;
                    lines.set(last, ellipsize(lines.get(last) + "...", width));
                }
            }
            return lines;
        }

        private String ellipsize(String value, float width) {
            String cleanValue = clean(value);
            if (paint.measureText(cleanValue) <= width) {
                return cleanValue;
            }
            String suffix = "...";
            int end = cleanValue.length();
            while (end > 0 && paint.measureText(cleanValue.substring(0, end) + suffix) > width) {
                end--;
            }
            return cleanValue.substring(0, Math.max(0, end)) + suffix;
        }

        private void drawSingleLineEllipsized(String value, float x, float baseline, float width) {
            canvas.drawText(ellipsize(value, width), x, baseline, paint);
        }

        private String resolveStudyLabel() {
            ZutnikSession session = ZutnikSession.getInstance(context);
            List<String> values = new ArrayList<>();
            addPart(values, clean(session.getUsername()));
            Study study = session.getActiveStudy();
            if (study != null) {
                addPart(values, clean(study.label));
            }
            String album = clean(session.getStudentNumber());
            if (!album.isEmpty()) {
                addPart(values, context.getString(R.string.calendar_export_pdf_album, album));
            }
            return String.join(" | ", values);
        }
    }

    private static String minutesToTime(int minutes) {
        int safe = Math.max(0, minutes);
        return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60);
    }

    private static String formatDate(LocalDate date, Locale locale) {
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale));
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void addPart(List<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value.trim());
        }
    }

    private static String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? clean(fallback) : cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String sanitizeFileName(String value) {
        String safe = clean(value).replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.isEmpty() ? "zutnik-plan-semestru.pdf" : safe;
    }
}
