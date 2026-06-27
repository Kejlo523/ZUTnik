package pl.kejlo.mzutv2;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PlanCalendarExportHelper {

    public static final String EXTRA_VIEW_MODE = "extra_view_mode";
    public static final String EXTRA_CURRENT_DATE = "extra_current_date";
    public static final String EXTRA_HIDDEN_SUBJECT_KEYS = "extra_hidden_subject_keys";
    public static final String EXTRA_SEARCH_CATEGORY = "extra_search_category";
    public static final String EXTRA_SEARCH_QUERY = "extra_search_query";

    public enum ExportScope {
        CURRENT_VIEW,
        SEMESTER
    }

    private static final ZoneId EXPORT_TIME_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter ICS_LOCAL_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter ICS_UTC_DT = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int CALENDAR_IMPORT_BATCH_SIZE = 40;
    private static final String IMPORT_MARKER_URI = "mzutv2://calendar-import";
    private static final String IMPORT_MARKER_DESC = "[ZUTnik import]";

    private final Context appContext;
    private final PlanRepository planRepository;
    private final String viewModeId;
    private final LocalDate currentDate;
    private final Set<String> hiddenSubjectKeys;
    private final PlanRepository.SearchParams searchParams;

    public PlanCalendarExportHelper(
            Context context,
            String viewModeId,
            LocalDate currentDate,
            Set<String> hiddenSubjectKeys,
            PlanRepository.SearchParams searchParams) {
        this.appContext = context.getApplicationContext();
        this.planRepository = new PlanRepository(appContext);
        this.viewModeId = viewModeId == null ? "week" : viewModeId;
        this.currentDate = currentDate == null ? LocalDate.now() : currentDate;
        this.hiddenSubjectKeys = hiddenSubjectKeys != null
                ? new LinkedHashSet<>(hiddenSubjectKeys)
                : new LinkedHashSet<>();
        this.searchParams = searchParams;
    }

    public static PlanCalendarExportHelper fromIntent(Context context, Intent intent) {
        String viewMode = intent != null ? intent.getStringExtra(EXTRA_VIEW_MODE) : "week";
        LocalDate currentDate = LocalDate.now();
        if (intent != null) {
            String currentDateRaw = intent.getStringExtra(EXTRA_CURRENT_DATE);
            if (currentDateRaw != null && !currentDateRaw.trim().isEmpty()) {
                try {
                    currentDate = LocalDate.parse(currentDateRaw.trim());
                } catch (Exception ignored) {
                }
            }
        }

        Set<String> hiddenKeys = new LinkedHashSet<>();
        if (intent != null) {
            ArrayList<String> hidden = intent.getStringArrayListExtra(EXTRA_HIDDEN_SUBJECT_KEYS);
            if (hidden != null) {
                hiddenKeys.addAll(hidden);
            }
        }

        PlanRepository.SearchParams params = null;
        if (intent != null) {
            String category = trimToEmpty(intent.getStringExtra(EXTRA_SEARCH_CATEGORY));
            String query = trimToEmpty(intent.getStringExtra(EXTRA_SEARCH_QUERY));
            if (!category.isEmpty() && !query.isEmpty()) {
                params = new PlanRepository.SearchParams();
                params.category = category;
                params.query = query;
            }
        }

        return new PlanCalendarExportHelper(context, viewMode, currentDate, hiddenKeys, params);
    }

    public ExportResult buildExport(ExportScope scope) throws Exception {
        List<CalendarExportEvent> events = collectEvents(scope);
        return new ExportResult(
                events,
                buildCalendarIcs(events),
                buildFileName(scope, events));
    }

    public List<DeviceCalendarInfo> loadWritableCalendars() {
        List<DeviceCalendarInfo> out = new ArrayList<>();
        String[] projection = new String[] {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS
        };

        // Do not require VISIBLE=1 here. Newly created calendars may not be marked visible
        // immediately, but they can still be valid write targets.
        String selection = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;

        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            if (cursor == null) {
                return out;
            }
            int idxId = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID);
            int idxName = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME);
            int idxAccount = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME);
            int idxType = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE);
            int idxVisible = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE);
            int idxSync = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.SYNC_EVENTS);
            while (cursor.moveToNext()) {
                out.add(new DeviceCalendarInfo(
                        cursor.getLong(idxId),
                        trimToEmpty(cursor.getString(idxName)),
                        trimToEmpty(cursor.getString(idxAccount)),
                        trimToEmpty(cursor.getString(idxType)),
                        cursor.getInt(idxVisible) == 1,
                        cursor.getInt(idxSync) == 1));
            }
        }

        out.sort(Comparator
                .comparing((DeviceCalendarInfo info) -> !info.visible)
                .thenComparing(info -> !info.syncEvents)
                .thenComparing(info -> trimToEmpty(info.displayName).toLowerCase(Locale.ROOT))
                .thenComparing(info -> trimToEmpty(info.accountName).toLowerCase(Locale.ROOT)));
        return out;
    }

    public int importIntoDeviceCalendar(DeviceCalendarInfo calendarInfo, ExportScope scope) throws Exception {
        if (calendarInfo == null) {
            return 0;
        }
        ExportResult export = buildExport(scope);
        ContentResolver resolver = appContext.getContentResolver();
        int inserted = 0;
        List<ContentValues> pendingBatch = new ArrayList<>(CALENDAR_IMPORT_BATCH_SIZE);
        CalendarKind calendarKind = resolveCalendarKind(calendarInfo);
        List<EventColorOption> colorOptions = loadEventColorOptions(calendarInfo, calendarKind);

        for (CalendarExportEvent item : export.events) {
            if (item == null || item.date == null || item.event == null) {
                continue;
            }
            pendingBatch.add(buildEventValues(calendarInfo, calendarKind, item, colorOptions));
            if (pendingBatch.size() >= CALENDAR_IMPORT_BATCH_SIZE) {
                inserted += flushCalendarInsertBatch(resolver, pendingBatch);
            }
        }

        if (!pendingBatch.isEmpty()) {
            inserted += flushCalendarInsertBatch(resolver, pendingBatch);
        }

        try {
            resolver.notifyChange(CalendarContract.Events.CONTENT_URI, null);
        } catch (Exception ignored) {
        }

        return inserted;
    }

    public int clearImportedEventsFromCalendar(DeviceCalendarInfo calendarInfo) {
        if (calendarInfo == null) {
            return 0;
        }

        ContentResolver resolver = appContext.getContentResolver();
        String selection = CalendarContract.Events.CALENDAR_ID + "=? AND ("
                + CalendarContract.Events.CUSTOM_APP_PACKAGE + "=? OR "
                + CalendarContract.Events.CUSTOM_APP_URI + "=? OR "
                + CalendarContract.Events.DESCRIPTION + " LIKE ?)";
        String[] args = new String[] {
                String.valueOf(calendarInfo.id),
                appContext.getPackageName(),
                IMPORT_MARKER_URI,
                "%" + IMPORT_MARKER_DESC + "%"
        };

        try {
            return resolver.delete(CalendarContract.Events.CONTENT_URI, selection, args);
        } catch (Exception e) {
            android.util.Log.e("PlanCalendarExport", "Failed to clear imported events", e);
            return 0;
        }
    }

    public static Uri writeShareCacheFile(Context context, String content, String fileName) throws Exception {
        if (context == null || content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Missing content for shared ICS file");
        }

        File dir = new File(context.getCacheDir(), "shared_calendar");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create cache directory for shared calendar");
        }

        String safeFileName = trimToEmpty(fileName);
        if (safeFileName.isEmpty()) {
            safeFileName = "mzut-plan.ics";
        }

        File outFile = new File(dir, safeFileName);
        try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush();
        }

        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                outFile);
    }

    private List<CalendarExportEvent> collectEvents(ExportScope scope) throws Exception {
        Map<String, CalendarExportEvent> collected = new LinkedHashMap<>();

        if (scope == ExportScope.SEMESTER) {
            LocalDate[] semesterRange = resolveSemesterRange();
            LocalDate cursor = semesterRange[0];
            while (!cursor.isAfter(semesterRange[1])) {
                PlanRepository.PlanResult result = loadPlanResult("week", cursor);
                appendEvents(collected, result, semesterRange[0], semesterRange[1]);
                cursor = cursor.plusWeeks(1);
            }
        } else if ("month".equals(viewModeId)) {
            LocalDate monthStart = currentDate.with(TemporalAdjusters.firstDayOfMonth());
            LocalDate monthEnd = currentDate.with(TemporalAdjusters.lastDayOfMonth());
            LocalDate cursor = monthStart;
            while (!cursor.isAfter(monthEnd)) {
                PlanRepository.PlanResult result = loadPlanResult("week", cursor);
                appendEvents(collected, result, monthStart, monthEnd);
                cursor = cursor.plusWeeks(1);
            }
        } else {
            PlanRepository.PlanResult result = loadPlanResult(viewModeId, currentDate);
            appendEvents(collected, result, null, null);
        }

        List<CalendarExportEvent> events = new ArrayList<>(collected.values());
        events.sort(Comparator
                .comparing((CalendarExportEvent item) -> item.date)
                .thenComparingInt(item -> item.event.startMin)
                .thenComparing(item -> trimToEmpty(item.event.title)));
        return events;
    }

    private void appendEvents(
            Map<String, CalendarExportEvent> out,
            PlanRepository.PlanResult result,
            LocalDate minDate,
            LocalDate maxDate) {
        if (out == null || result == null || result.dayColumns == null) {
            return;
        }

        for (PlanRepository.DayColumn column : getVisibleColumns(result.dayColumns)) {
            if (column == null || column.date == null || column.events == null) {
                continue;
            }
            if (minDate != null && column.date.isBefore(minDate)) {
                continue;
            }
            if (maxDate != null && column.date.isAfter(maxDate)) {
                continue;
            }

            for (PlanRepository.PlanEventUi event : column.events) {
                if (event == null || shouldHideEvent(event)) {
                    continue;
                }
                String key = buildEventKey(column.date, event);
                if (!out.containsKey(key)) {
                    out.put(key, new CalendarExportEvent(column.date, event));
                }
            }
        }
    }

    private PlanRepository.PlanResult loadPlanResult(String modeId, LocalDate date) throws Exception {
        if (searchParams != null && searchParams.category != null && searchParams.query != null) {
            return planRepository.searchPlan(modeId, date, searchParams);
        }
        return planRepository.loadPlan(modeId, date);
    }

    private List<PlanRepository.DayColumn> getVisibleColumns(List<PlanRepository.DayColumn> allColumns) {
        if (allColumns == null || allColumns.isEmpty()) {
            return new ArrayList<>();
        }

        int satIndex = -1;
        int sunIndex = -1;
        boolean satHasEvents = false;
        boolean sunHasEvents = false;

        for (int i = 0; i < allColumns.size(); i++) {
            PlanRepository.DayColumn col = allColumns.get(i);
            if (col == null || col.date == null) {
                continue;
            }
            switch (col.date.getDayOfWeek()) {
                case SATURDAY:
                    satIndex = i;
                    satHasEvents = col.events != null && !col.events.isEmpty();
                    break;
                case SUNDAY:
                    sunIndex = i;
                    sunHasEvents = col.events != null && !col.events.isEmpty();
                    break;
                default:
                    break;
            }
        }

        if (satIndex != -1 && sunIndex != -1 && !satHasEvents && !sunHasEvents) {
            List<PlanRepository.DayColumn> filtered = new ArrayList<>();
            for (int i = 0; i < allColumns.size(); i++) {
                if (i == satIndex || i == sunIndex) {
                    continue;
                }
                filtered.add(allColumns.get(i));
            }
            return filtered;
        }
        return allColumns;
    }

    private boolean shouldHideEvent(PlanRepository.PlanEventUi event) {
        if (event == null || event.subjectKey == null || event.subjectKey.isEmpty()) {
            return false;
        }
        return hiddenSubjectKeys.contains(event.subjectKey);
    }

    private String buildCalendarIcs(List<CalendarExportEvent> events) {
        StringBuilder sb = new StringBuilder();
        appendIcsLine(sb, "BEGIN:VCALENDAR");
        appendIcsLine(sb, "VERSION:2.0");
        appendIcsLine(sb, "PRODID:-//ZUTnik//Plan Export//PL");
        appendIcsLine(sb, "CALSCALE:GREGORIAN");
        appendIcsLine(sb, "METHOD:PUBLISH");
        appendIcsLine(sb, "X-WR-CALNAME:" + escapeIcsText(appContext.getString(R.string.plan_title)));
        appendIcsLine(sb, "X-WR-TIMEZONE:" + EXPORT_TIME_ZONE.getId());

        String dtStamp = ICS_UTC_DT.format(Instant.now());
        for (CalendarExportEvent item : events) {
            if (item == null || item.date == null || item.event == null) {
                continue;
            }

            int endMin = item.event.endMin > item.event.startMin
                    ? item.event.endMin
                    : item.event.startMin + 60;
            LocalDateTime start = item.date.atStartOfDay().plusMinutes(item.event.startMin);
            LocalDateTime end = item.date.atStartOfDay().plusMinutes(endMin);

            appendIcsLine(sb, "BEGIN:VEVENT");
            appendIcsLine(sb, "UID:" + buildIcsUid(item));
            appendIcsLine(sb, "DTSTAMP:" + dtStamp);
            appendIcsLine(sb, "DTSTART;TZID=" + EXPORT_TIME_ZONE.getId() + ":" + ICS_LOCAL_DT.format(start));
            appendIcsLine(sb, "DTEND;TZID=" + EXPORT_TIME_ZONE.getId() + ":" + ICS_LOCAL_DT.format(end));
            appendIcsLine(sb, "SUMMARY:" + escapeIcsText(buildDeviceCalendarTitle(item.event)));

            String location = trimToEmpty(item.event.room);
            if (!location.isEmpty()) {
                appendIcsLine(sb, "LOCATION:" + escapeIcsText(location));
            }

            String description = buildCalendarDescription(item.event);
            if (!description.isEmpty()) {
                appendIcsLine(sb, "DESCRIPTION:" + escapeIcsText(description));
            }

            if (isCancelled(item.event)) {
                appendIcsLine(sb, "STATUS:CANCELLED");
            }
            appendIcsLine(sb, "END:VEVENT");
        }

        appendIcsLine(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private String buildFileName(ExportScope scope, List<CalendarExportEvent> events) {
        if (scope == ExportScope.SEMESTER) {
            LocalDate[] range = extractDateRange(events);
            return "mzut-plan-semestr-"
                    + range[0].format(YMD)
                    + "_do_"
                    + range[1].format(YMD)
                    + ".ics";
        }

        if ("day".equals(viewModeId)) {
            return "mzut-plan-dzien-" + currentDate.format(YMD) + ".ics";
        }
        if ("month".equals(viewModeId)) {
            return "mzut-plan-miesiac-"
                    + currentDate.with(TemporalAdjusters.firstDayOfMonth())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.getDefault()))
                    + ".ics";
        }
        return "mzut-plan-tydzien-" + currentDate.format(YMD) + ".ics";
    }

    private String buildDeviceCalendarTitle(PlanRepository.PlanEventUi event) {
        String title = trimToEmpty(event != null ? event.title : "");
        if (title.isEmpty()) {
            title = appContext.getString(R.string.plan_export_ics_default_title);
        }
        if (isCancelled(event)) {
            return appContext.getString(R.string.plan_export_calendar_cancelled_prefix) + " " + title;
        }
        return title;
    }

    private ContentValues buildEventValues(
            DeviceCalendarInfo calendarInfo,
            CalendarKind calendarKind,
            CalendarExportEvent item,
            List<EventColorOption> colorOptions) {
        long calendarId = calendarInfo != null ? calendarInfo.id : 0L;
        int endMin = item.event.endMin > item.event.startMin
                ? item.event.endMin
                : item.event.startMin + 60;
        LocalDateTime start = item.date.atStartOfDay().plusMinutes(item.event.startMin);
        LocalDateTime end = item.date.atStartOfDay().plusMinutes(endMin);

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, buildDeviceCalendarTitle(item.event));
        values.put(CalendarContract.Events.DTSTART, toEpochMillis(start));
        values.put(CalendarContract.Events.DTEND, toEpochMillis(end));
        values.put(CalendarContract.Events.EVENT_TIMEZONE, EXPORT_TIME_ZONE.getId());
        values.put(CalendarContract.Events.DESCRIPTION, appendImportMarker(buildCalendarDescription(item.event)));
        values.put(CalendarContract.Events.EVENT_LOCATION, trimToEmpty(item.event.room));
        values.put(CalendarContract.Events.STATUS,
                isCancelled(item.event)
                        ? CalendarContract.Events.STATUS_CANCELED
                        : CalendarContract.Events.STATUS_CONFIRMED);
        values.put(CalendarContract.Events.CUSTOM_APP_PACKAGE, appContext.getPackageName());
        values.put(CalendarContract.Events.CUSTOM_APP_URI, IMPORT_MARKER_URI);
        int desiredColor = resolveDesiredEventColor(item.event, calendarKind);
        applyEventColorValues(values, calendarKind, colorOptions, desiredColor);
        return values;
    }

    private void applyEventColorValues(
            ContentValues values,
            CalendarKind calendarKind,
            List<EventColorOption> colorOptions,
            int desiredColor) {
        if (values == null) {
            return;
        }

        values.put(CalendarContract.Events.EVENT_COLOR, desiredColor);

        if (calendarKind != CalendarKind.GOOGLE) {
            return;
        }

        String colorKey = resolveEventColorKey(colorOptions, desiredColor);
        if (!trimToEmpty(colorKey).isEmpty()) {
            values.put(CalendarContract.Events.EVENT_COLOR_KEY, colorKey);
        }
    }

    private int flushCalendarInsertBatch(ContentResolver resolver, List<ContentValues> valuesBatch) {
        if (resolver == null || valuesBatch == null || valuesBatch.isEmpty()) {
            return 0;
        }

        List<ContentValues> snapshot = new ArrayList<>(valuesBatch);
        valuesBatch.clear();

        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>(snapshot.size());
            for (ContentValues values : snapshot) {
                ops.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                        .withValues(values)
                        .build());
            }
            ContentProviderResult[] results = resolver.applyBatch(CalendarContract.AUTHORITY, ops);
            for (int i = 0; i < results.length && i < snapshot.size(); i++) {
                ContentProviderResult result = results[i];
                if (result != null && result.uri != null) {
                    maybeUpdateInsertedEventColor(resolver, result.uri, snapshot.get(i));
                }
            }
            return results.length;
        } catch (Exception batchError) {
            android.util.Log.w("PlanCalendarExport", "Batch calendar insert failed, falling back to single inserts", batchError);
            int inserted = 0;
            for (ContentValues values : snapshot) {
                try {
                    Uri insertedUri = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
                    if (insertedUri != null) {
                        maybeUpdateInsertedEventColor(resolver, insertedUri, values);
                        inserted++;
                    }
                } catch (Exception singleError) {
                    android.util.Log.w("PlanCalendarExport", "Single calendar insert failed", singleError);
                }
            }
            return inserted;
        }
    }

    private void maybeUpdateInsertedEventColor(
            ContentResolver resolver,
            Uri insertedUri,
            ContentValues sourceValues) {
        if (resolver == null || insertedUri == null || sourceValues == null) {
            return;
        }

        ContentValues colorValues = new ContentValues();
        Integer eventColor = sourceValues.getAsInteger(CalendarContract.Events.EVENT_COLOR);
        if (eventColor != null) {
            colorValues.put(CalendarContract.Events.EVENT_COLOR, eventColor);
        }

        String eventColorKey = sourceValues.getAsString(CalendarContract.Events.EVENT_COLOR_KEY);
        if (!trimToEmpty(eventColorKey).isEmpty()) {
            colorValues.put(CalendarContract.Events.EVENT_COLOR_KEY, eventColorKey);
        }

        if (colorValues.size() < 1) {
            return;
        }

        try {
            resolver.update(insertedUri, colorValues, null, null);
        } catch (Exception colorError) {
            android.util.Log.w("PlanCalendarExport", "Event color update after insert failed", colorError);
        }
    }

    private int resolveDesiredEventColor(PlanRepository.PlanEventUi event, CalendarKind kind) {
        if (event == null) {
            return appContext.getColor(R.color.mz_primary);
        }
        String typeClass = trimToEmpty(event.typeClass).toLowerCase(Locale.ROOT);
        boolean strongPalette = kind == CalendarKind.GOOGLE || kind == CalendarKind.SAMSUNG;
        if (typeClass.contains("cancelled")) {
            return appContext.getColor(strongPalette ? R.color.mz_muted : R.color.plan_event_cancelled_bg);
        }
        if (typeClass.contains("exam")) {
            return appContext.getColor(strongPalette ? R.color.mz_danger : R.color.plan_event_exam_bg);
        }
        if (typeClass.contains("lab")) {
            return appContext.getColor(strongPalette ? R.color.mz_primary_alt : R.color.plan_event_lab_bg);
        }
        if (typeClass.contains("auditory") || typeClass.contains("project")) {
            return appContext.getColor(strongPalette ? R.color.mz_accent : R.color.plan_event_auditory_bg);
        }
        if (typeClass.contains("remote")) {
            return appContext.getColor(strongPalette ? R.color.mz_primary_alt : R.color.plan_event_remote_bg);
        }
        if (typeClass.contains("pass")) {
            return appContext.getColor(strongPalette ? R.color.mz_success : R.color.plan_event_pass_bg);
        }
        if (typeClass.contains("lecture") || typeClass.contains("lec")) {
            return appContext.getColor(strongPalette ? R.color.mz_primary : R.color.plan_event_lecture_bg);
        }
        if (event.isCustomEvent) {
            return appContext.getColor(R.color.mz_accent);
        }
        return appContext.getColor(strongPalette ? R.color.mz_primary : R.color.plan_event_default_bg);
    }

    private List<EventColorOption> loadEventColorOptions(
            DeviceCalendarInfo calendarInfo,
            CalendarKind calendarKind) {
        List<EventColorOption> out = new ArrayList<>();
        if (calendarInfo == null || calendarKind != CalendarKind.GOOGLE) {
            return out;
        }

        String[] projection = new String[] {
                CalendarContract.Colors.COLOR_KEY,
                CalendarContract.Colors.COLOR
        };

        String selection = CalendarContract.Colors.COLOR_TYPE + "=? AND "
                + CalendarContract.Colors.ACCOUNT_NAME + "=? AND "
                + CalendarContract.Colors.ACCOUNT_TYPE + "=?";
        String[] args = new String[] {
                String.valueOf(CalendarContract.Colors.TYPE_EVENT),
                trimToEmpty(calendarInfo.accountName),
                trimToEmpty(calendarInfo.accountType)
        };

        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                CalendarContract.Colors.CONTENT_URI,
                projection,
                selection,
                args,
                null)) {
            if (cursor != null) {
                int idxKey = cursor.getColumnIndexOrThrow(CalendarContract.Colors.COLOR_KEY);
                int idxColor = cursor.getColumnIndexOrThrow(CalendarContract.Colors.COLOR);
                while (cursor.moveToNext()) {
                    out.add(new EventColorOption(
                            trimToEmpty(cursor.getString(idxKey)),
                            cursor.getInt(idxColor)));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PlanCalendarExport", "Failed to query event colors for account", e);
        }

        if (!out.isEmpty()) {
            return out;
        }

        String fallbackSelection = CalendarContract.Colors.COLOR_TYPE + "=?";
        String[] fallbackArgs = new String[] {
                String.valueOf(CalendarContract.Colors.TYPE_EVENT)
        };
        try (android.database.Cursor cursor = appContext.getContentResolver().query(
                CalendarContract.Colors.CONTENT_URI,
                projection,
                fallbackSelection,
                fallbackArgs,
                null)) {
            if (cursor != null) {
                int idxKey = cursor.getColumnIndexOrThrow(CalendarContract.Colors.COLOR_KEY);
                int idxColor = cursor.getColumnIndexOrThrow(CalendarContract.Colors.COLOR);
                while (cursor.moveToNext()) {
                    out.add(new EventColorOption(
                            trimToEmpty(cursor.getString(idxKey)),
                            cursor.getInt(idxColor)));
                }
            }
        } catch (Exception e) {
            android.util.Log.w("PlanCalendarExport", "Failed to query fallback event colors", e);
        }

        return out;
    }

    private String resolveEventColorKey(List<EventColorOption> options, int desiredColor) {
        if (options == null || options.isEmpty()) {
            return "";
        }

        EventColorOption best = null;
        long bestDistance = Long.MAX_VALUE;
        for (EventColorOption option : options) {
            if (option == null || trimToEmpty(option.colorKey).isEmpty()) {
                continue;
            }
            long distance = colorDistance(option.colorValue, desiredColor);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = option;
            }
        }
        return best != null ? best.colorKey : "";
    }

    private CalendarKind resolveCalendarKind(DeviceCalendarInfo calendarInfo) {
        if (calendarInfo != null) {
            if (calendarInfo.isGoogleCalendar()) {
                return CalendarKind.GOOGLE;
            }
            if (calendarInfo.isSamsungCalendar()) {
                return CalendarKind.SAMSUNG;
            }
        }
        if (Build.MANUFACTURER != null && Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("samsung")) {
            return CalendarKind.SAMSUNG;
        }
        return CalendarKind.OTHER;
    }

    private long colorDistance(int first, int second) {
        int fr = (first >> 16) & 0xFF;
        int fg = (first >> 8) & 0xFF;
        int fb = first & 0xFF;
        int sr = (second >> 16) & 0xFF;
        int sg = (second >> 8) & 0xFF;
        int sb = second & 0xFF;

        long dr = fr - sr;
        long dg = fg - sg;
        long db = fb - sb;
        return dr * dr + dg * dg + db * db;
    }

    private String appendImportMarker(String description) {
        String body = trimToEmpty(description);
        if (body.isEmpty()) {
            return IMPORT_MARKER_DESC;
        }
        if (body.contains(IMPORT_MARKER_DESC)) {
            return body;
        }
        return body + "\n" + IMPORT_MARKER_DESC;
    }

    private String buildCalendarDescription(PlanRepository.PlanEventUi event) {
        if (event == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        if (!trimToEmpty(event.typeLabel).isEmpty()) {
            lines.add(appContext.getString(R.string.plan_change_field_type) + ": " + trimToEmpty(event.typeLabel));
        }
        if (!trimToEmpty(event.group).isEmpty()) {
            lines.add(appContext.getString(R.string.plan_change_field_group) + ": " + trimToEmpty(event.group));
        }
        if (!trimToEmpty(event.teacher).isEmpty()) {
            lines.add(appContext.getString(R.string.plan_change_field_teacher) + ": " + trimToEmpty(event.teacher));
        }
        if (event.isCustomEvent) {
            lines.add(appContext.getString(R.string.plan_export_ics_custom_event));
        }
        return lines.isEmpty() ? "" : TextUtils.join("\n", lines);
    }

    private LocalDate[] resolveSemesterRange() {
        LocalDate fallbackStart = defaultSemesterStart(currentDate);
        LocalDate fallbackEnd = defaultSemesterEnd(currentDate);

        List<PlanRepository.SessionPeriod> periods = PlanRepository.getCachedSessionDates(appContext);
        if (periods.isEmpty()) {
            periods = planRepository.fetchSessionDates();
        }
        if (periods.isEmpty()) {
            return new LocalDate[] { fallbackStart, fallbackEnd };
        }

        List<PlanRepository.SessionPeriod> noClasses = new ArrayList<>();
        for (PlanRepository.SessionPeriod period : periods) {
            if (period == null || period.startDate == null || period.endDate == null) {
                continue;
            }
            if (PlanRepository.isNoClassesPeriodName(period.name)) {
                noClasses.add(period);
            }
        }
        if (noClasses.isEmpty()) {
            return new LocalDate[] { fallbackStart, fallbackEnd };
        }

        noClasses.sort(Comparator.comparing(period -> period.startDate));
        PlanRepository.SessionPeriod activeBreak = null;
        PlanRepository.SessionPeriod previousBreak = null;
        PlanRepository.SessionPeriod nextBreak = null;

        for (PlanRepository.SessionPeriod period : noClasses) {
            if (period.contains(currentDate)) {
                activeBreak = period;
                break;
            }
            if (period.endDate.isBefore(currentDate)) {
                previousBreak = period;
                continue;
            }
            if (period.startDate.isAfter(currentDate)) {
                nextBreak = period;
                break;
            }
        }

        if (activeBreak != null) {
            long daysFromStart = ChronoUnit.DAYS.between(activeBreak.startDate, currentDate);
            long daysToEnd = ChronoUnit.DAYS.between(currentDate, activeBreak.endDate);
            if (daysFromStart <= daysToEnd) {
                LocalDate end = activeBreak.startDate.minusDays(1);
                LocalDate start = previousBreak != null
                        ? previousBreak.endDate.plusDays(1)
                        : defaultSemesterStart(end);
                return sanitizeSemesterRange(start, end, fallbackStart, fallbackEnd);
            }

            PlanRepository.SessionPeriod afterActive = null;
            boolean passedActive = false;
            for (PlanRepository.SessionPeriod period : noClasses) {
                if (!passedActive) {
                    if (period == activeBreak) {
                        passedActive = true;
                    }
                    continue;
                }
                afterActive = period;
                break;
            }
            LocalDate start = activeBreak.endDate.plusDays(1);
            LocalDate end = afterActive != null
                    ? afterActive.startDate.minusDays(1)
                    : defaultSemesterEnd(start);
            return sanitizeSemesterRange(start, end, fallbackStart, fallbackEnd);
        }

        LocalDate start = previousBreak != null
                ? previousBreak.endDate.plusDays(1)
                : fallbackStart;
        LocalDate end = nextBreak != null
                ? nextBreak.startDate.minusDays(1)
                : fallbackEnd;
        return sanitizeSemesterRange(start, end, fallbackStart, fallbackEnd);
    }

    private LocalDate[] sanitizeSemesterRange(
            LocalDate start,
            LocalDate end,
            LocalDate fallbackStart,
            LocalDate fallbackEnd) {
        if (start == null || end == null || end.isBefore(start)) {
            return new LocalDate[] { fallbackStart, fallbackEnd };
        }
        return new LocalDate[] { start, end };
    }

    private LocalDate defaultSemesterStart(LocalDate anchor) {
        LocalDate date = anchor != null ? anchor : LocalDate.now();
        int month = date.getMonthValue();
        if (month >= 2 && month <= 7) {
            return LocalDate.of(date.getYear(), 2, 1);
        }
        if (month == 1) {
            return LocalDate.of(date.getYear() - 1, 9, 1);
        }
        return LocalDate.of(date.getYear(), 9, 1);
    }

    private LocalDate defaultSemesterEnd(LocalDate anchor) {
        LocalDate date = anchor != null ? anchor : LocalDate.now();
        int month = date.getMonthValue();
        if (month >= 2 && month <= 7) {
            return LocalDate.of(date.getYear(), 7, 31);
        }
        if (month == 1) {
            return LocalDate.of(date.getYear(), 1, 31);
        }
        return LocalDate.of(date.getYear() + 1, 1, 31);
    }

    private String buildEventKey(LocalDate date, PlanRepository.PlanEventUi event) {
        return date
                + "|" + event.startMin
                + "|" + event.endMin
                + "|" + trimToEmpty(event.title)
                + "|" + trimToEmpty(event.room)
                + "|" + trimToEmpty(event.group)
                + "|" + trimToEmpty(event.teacher)
                + "|" + trimToEmpty(event.typeLabel)
                + "|" + (event.isCustomEvent ? "1" : "0");
    }

    private String buildIcsUid(CalendarExportEvent item) {
        return Integer.toHexString(buildEventKey(item.date, item.event).hashCode()) + "@mzutv2";
    }

    private LocalDate[] extractDateRange(List<CalendarExportEvent> events) {
        if (events == null || events.isEmpty()) {
            return new LocalDate[] { currentDate, currentDate };
        }
        LocalDate start = events.get(0).date != null ? events.get(0).date : currentDate;
        LocalDate end = events.get(events.size() - 1).date != null
                ? events.get(events.size() - 1).date
                : start;
        return new LocalDate[] { start, end };
    }

    private long toEpochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(EXPORT_TIME_ZONE).toInstant().toEpochMilli();
    }

    private boolean isCancelled(PlanRepository.PlanEventUi event) {
        return event != null
                && event.typeClass != null
                && "week-event-type-cancelled".equalsIgnoreCase(event.typeClass.trim());
    }

    private void appendIcsLine(StringBuilder sb, String line) {
        sb.append(line).append("\r\n");
    }

    private String escapeIcsText(String value) {
        return trimToEmpty(value)
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ExportResult {
        public final List<CalendarExportEvent> events;
        public final String icsContent;
        public final String fileName;

        ExportResult(List<CalendarExportEvent> events, String icsContent, String fileName) {
            this.events = events;
            this.icsContent = icsContent;
            this.fileName = fileName;
        }
    }

    public static final class DeviceCalendarInfo {
        public final long id;
        public final String displayName;
        public final String accountName;
        public final String accountType;
        public final boolean visible;
        public final boolean syncEvents;

        DeviceCalendarInfo(
                long id,
                String displayName,
                String accountName,
                String accountType,
                boolean visible,
                boolean syncEvents) {
            this.id = id;
            this.displayName = displayName;
            this.accountName = accountName;
            this.accountType = accountType;
            this.visible = visible;
            this.syncEvents = syncEvents;
        }

        public String toDisplayLabel(Context context) {
            String name = trimToEmpty(displayName);
            if (name.isEmpty()) {
                name = context != null
                        ? context.getString(R.string.plan_export_calendar_default_name)
                        : "Kalendarz";
            }

            String account = trimToEmpty(accountName);
            String base = account.isEmpty()
                    ? name
                    : name + " (" + account + ")";

            String providerLabel = resolveProviderLabel(context);
            if (!providerLabel.isEmpty()) {
                base = "[" + providerLabel + "] " + base;
            }
            return appendStateSuffix(context, base);
        }

        private String appendStateSuffix(Context context, String base) {
            String label = base == null ? "" : base;
            if (!visible && context != null) {
                label += " • " + context.getString(R.string.calendar_export_calendar_hidden);
            } else if (!syncEvents && context != null) {
                label += " • " + context.getString(R.string.calendar_export_calendar_no_sync);
            }
            return label;
        }

        public boolean isGoogleCalendar() {
            String type = normalize(accountType);
            if (type.contains("com.google") || type.equals("google") || type.contains(".google")) {
                return true;
            }

            String account = normalize(accountName);
            return account.endsWith("@gmail.com") || account.endsWith("@googlemail.com");
        }

        public boolean isSamsungCalendar() {
            String type = normalize(accountType);
            if (type.contains("com.samsung") || type.equals("samsung") || type.contains(".samsung")) {
                return true;
            }

            String account = normalize(accountName);
            if (account.contains("samsung")) {
                return true;
            }

            String name = normalize(displayName);
            return "my calendar".equals(name) || name.contains("samsung calendar");
        }

        private static String normalize(String value) {
            return trimToEmpty(value).toLowerCase(Locale.ROOT);
        }

        private String resolveProviderLabel(Context context) {
            if (isGoogleCalendar()) {
                return context != null
                        ? context.getString(R.string.calendar_export_provider_google)
                        : "Google";
            }
            if (isSamsungCalendar()) {
                return context != null
                        ? context.getString(R.string.calendar_export_provider_samsung)
                        : "Samsung";
            }
            return context != null
                    ? context.getString(R.string.calendar_export_provider_other)
                    : "Other";
        }
    }

    private static final class EventColorOption {
        final String colorKey;
        final int colorValue;

        EventColorOption(String colorKey, int colorValue) {
            this.colorKey = colorKey;
            this.colorValue = colorValue;
        }
    }

    private enum CalendarKind {
        GOOGLE,
        SAMSUNG,
        OTHER
    }

    public static final class CalendarExportEvent {
        final LocalDate date;
        final PlanRepository.PlanEventUi event;

        CalendarExportEvent(LocalDate date, PlanRepository.PlanEventUi event) {
            this.date = date;
            this.event = event;
        }
    }
}
