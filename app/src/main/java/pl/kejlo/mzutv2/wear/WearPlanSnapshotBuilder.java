package pl.kejlo.mzutv2.wear;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.graphics.ColorUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import pl.kejlo.mzutv2.MzutSession;
import pl.kejlo.mzutv2.PlanRepository;
import pl.kejlo.mzutv2.R;
import pl.kejlo.mzutv2.SettingsPrefs;
import pl.kejlo.mzutv2.ThemeManager;

public class WearPlanSnapshotBuilder {

    private static final String PREFS_PLAN = "mzut_plan";
    private static final String KEY_FILTER_HIDDEN = "plan_hidden_filters_v2";

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    public static WearPlanSnapshot build(Context context) {
        if (context == null) {
            return null;
        }

        WearPlanSnapshot snap = new WearPlanSnapshot();
        applyThemePalette(context, snap);
        String languageTag = resolveAppLanguageTag(context);
        Locale snapshotLocale = parseLocale(languageTag);
        DateTimeFormatter dateLabelFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", snapshotLocale);
        DateTimeFormatter weekDayLabelFormatter = DateTimeFormatter.ofPattern("EEE dd.MM", snapshotLocale);
        DateTimeFormatter dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEEE", snapshotLocale);
        Context localizedContext = createLocalizedContext(context, snapshotLocale);
        snap.languageTag = languageTag;
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute();

        if (!ensureSessionFromPrefs(context)) {
            snap.loginRequired = true;
            snap.subtitle = localizedContext.getString(R.string.plan_widget_subtitle_login_required);
            snap.dateIso = today.toString();
            snap.dateLabel = today.format(dateLabelFormatter);
            snap.refreshedLabel = localizedContext.getString(R.string.plan_widget_refreshed_prefix)
                    + " " + now.format(TIME_LABEL);
            return snap;
        }

        try {
            PlanRepository repo = new PlanRepository(context.getApplicationContext());
            SharedPreferences planPrefs = context.getSharedPreferences(PREFS_PLAN, Context.MODE_PRIVATE);
            Set<String> hiddenKeys = planPrefs.getStringSet(KEY_FILTER_HIDDEN, new HashSet<>());

            PlanRepository.PlanResult weekResult = repo.loadPlan("week", today, true);
            PlanRepository.PlanResult nextWeekResult = repo.loadPlan("week", today.plusDays(7));

            if (nextWeekResult != null && nextWeekResult.dayColumns != null && weekResult != null) {
                if (weekResult.dayColumns == null) {
                    weekResult.dayColumns = new ArrayList<>();
                }
                for (PlanRepository.DayColumn col : nextWeekResult.dayColumns) {
                    boolean found = false;
                    for (PlanRepository.DayColumn existing : weekResult.dayColumns) {
                        if (existing.date != null && existing.date.equals(col.date)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        weekResult.dayColumns.add(col);
                    }
                }
            }

            LocalDate targetDate = findBestDateToShow(weekResult, today, nowMin, hiddenKeys);

            snap.dateIso = targetDate.toString();
            snap.dateLabel = targetDate.format(dateLabelFormatter);

            String subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_today);
            if (targetDate.equals(today)) {
                List<PlanRepository.PlanEventUi> eventsToday = getEventsForDate(weekResult, today, hiddenKeys);
                List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : eventsToday) {
                    if (ev.endMin > nowMin) {
                        upcoming.add(ev);
                    }
                }
                if (!upcoming.isEmpty()) {
                    PlanRepository.PlanEventUi next = upcoming.get(0);
                    if (next.startMin <= nowMin) {
                        subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_in_progress);
                    } else {
                        int diffMin = next.startMin - nowMin;
                        int h = diffMin / 60;
                        int m = diffMin % 60;
                        if (h > 0) {
                            String hours = h + localizedContext.getString(R.string.plan_widget_hours_suffix);
                            String minutes = m > 0
                                    ? " " + m + localizedContext.getString(R.string.plan_widget_minutes_suffix)
                                    : "";
                            subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_next_prefix)
                                    + hours + minutes;
                        } else {
                            subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_next_prefix)
                                    + m + localizedContext.getString(R.string.plan_widget_minutes_suffix);
                        }
                    }
                } else {
                    subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_today);
                }
            } else if (targetDate.equals(today.plusDays(1))) {
                subtitleText = localizedContext.getString(R.string.plan_widget_subtitle_tomorrow);
            } else {
                String dayName = targetDate.format(dayOfWeekFormatter);
                dayName = dayName.substring(0, 1).toUpperCase(snapshotLocale) + dayName.substring(1);
                subtitleText = dayName;
            }

            snap.subtitle = subtitleText;
            snap.refreshedLabel = localizedContext.getString(R.string.plan_widget_refreshed_prefix)
                    + " " + LocalTime.now().format(TIME_LABEL);

            // Build full week list (current week range)
            LocalDate weekStart = weekResult != null ? weekResult.rangeStart : today;
            LocalDate weekEnd = weekResult != null ? weekResult.rangeEnd : today.plusDays(6);
            if (weekStart == null) {
                weekStart = today;
            }
            if (weekEnd == null) {
                weekEnd = weekStart.plusDays(6);
            }
            LocalDate d = weekStart;
            while (!d.isAfter(weekEnd)) {
                WearPlanSnapshot.WeekDay day = new WearPlanSnapshot.WeekDay();
                day.dateIso = d.toString();
                day.dateLabel = d.format(weekDayLabelFormatter);
                List<PlanRepository.PlanEventUi> dayEventsFull = getEventsForDate(weekResult, d, hiddenKeys);
                for (PlanRepository.PlanEventUi ev : dayEventsFull) {
                    WearPlanSnapshot.Event e = new WearPlanSnapshot.Event();
                    e.title = ev.title != null ? ev.title : "";
                    e.time = ev.startStr + " \u2013 " + ev.endStr;
                    String roomStr = "";
                    if (ev.room != null && !ev.room.isEmpty())
                        roomStr = ev.room;
                    if (ev.group != null && !ev.group.isEmpty())
                        roomStr += (roomStr.isEmpty() ? "" : " \u00B7 ") + ev.group;
                    e.room = roomStr;
                    e.color = ThemeManager.resolveEventColor(context, ev.typeClass);
                    day.events.add(e);
                }
                snap.weekDays.add(day);
                d = d.plusDays(1);
            }

            // Events list for the target date
            PlanRepository.PlanResult dayResult = repo.loadPlan("day", targetDate);
            List<PlanRepository.PlanEventUi> dayEvents = new ArrayList<>();
            if (dayResult != null && dayResult.dayColumns != null) {
                for (PlanRepository.DayColumn col : dayResult.dayColumns) {
                    if (targetDate.equals(col.date) && col.events != null) {
                        for (PlanRepository.PlanEventUi ev : col.events) {
                            if (ev.subjectKey != null && hiddenKeys.contains(ev.subjectKey))
                                continue;
                            dayEvents.add(ev);
                        }
                        break;
                    }
                }
            }

            if (dayEvents.isEmpty()) {
                return snap;
            }

            Collections.sort(dayEvents, (a, b) -> Integer.compare(a.startMin, b.startMin));

            if (targetDate.equals(today)) {
                List<PlanRepository.PlanEventUi> upcoming = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : dayEvents) {
                    if (ev.endMin > nowMin) {
                        upcoming.add(ev);
                    }
                }
                dayEvents.clear();
                dayEvents.addAll(upcoming);
            }

            int limit = Math.min(dayEvents.size(), 5);
            for (int i = 0; i < limit; i++) {
                PlanRepository.PlanEventUi ev = dayEvents.get(i);
                WearPlanSnapshot.Event e = new WearPlanSnapshot.Event();
                e.title = ev.title != null ? ev.title : "";
                e.time = ev.startStr + " \u2013 " + ev.endStr;

                String roomStr = "";
                if (ev.room != null && !ev.room.isEmpty())
                    roomStr = ev.room;
                if (ev.group != null && !ev.group.isEmpty())
                    roomStr += (roomStr.isEmpty() ? "" : " \u00B7 ") + ev.group;
                e.room = roomStr;
                e.color = ThemeManager.resolveEventColor(context, ev.typeClass);
                snap.events.add(e);
            }

        } catch (Exception ignored) {
        }

        return snap;
    }

    private static String resolveAppLanguageTag(Context context) {
        if (context == null) {
            return SettingsPrefs.DEFAULT_APP_LANGUAGE;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        String raw = prefs.getString(
                SettingsPrefs.KEY_APP_LANGUAGE,
                SettingsPrefs.DEFAULT_APP_LANGUAGE);
        return normalizeLanguageTag(raw);
    }

    private static String normalizeLanguageTag(String raw) {
        if (raw == null) {
            return SettingsPrefs.DEFAULT_APP_LANGUAGE;
        }
        String normalized = raw.trim().replace('_', '-');
        if (normalized.isEmpty()) {
            return SettingsPrefs.DEFAULT_APP_LANGUAGE;
        }
        return normalized;
    }

    private static Locale parseLocale(String languageTag) {
        String normalized = normalizeLanguageTag(languageTag);
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale == null || locale.getLanguage() == null || locale.getLanguage().isEmpty()) {
            return Locale.getDefault();
        }
        return locale;
    }

    private static Context createLocalizedContext(Context context, Locale locale) {
        if (context == null || locale == null) {
            return context;
        }
        android.content.res.Configuration cfg =
                new android.content.res.Configuration(context.getResources().getConfiguration());
        cfg.setLocale(locale);
        return context.createConfigurationContext(cfg);
    }

    private static void applyThemePalette(Context context, WearPlanSnapshot snap) {
        String theme = ThemeManager.getTheme(context);
        snap.theme = theme;
        int themeRes;
        switch (theme) {
            case ThemeManager.THEME_DEEP_BLUE:
                themeRes = R.style.Theme_MZUTv2_DeepBlue;
                break;
            case ThemeManager.THEME_LIME:
                themeRes = R.style.Theme_MZUTv2_Lime;
                break;
            case ThemeManager.THEME_HIGH_CONTRAST:
                themeRes = R.style.Theme_MZUTv2_HighContrast;
                break;
            case ThemeManager.THEME_DEFAULT:
            default:
                themeRes = R.style.Theme_MZUTv2;
                break;
        }

        android.content.Context themedCtx =
                new android.view.ContextThemeWrapper(context, themeRes);

        int bg = ThemeManager.resolveColor(themedCtx, R.attr.mzBg);
        int card = ThemeManager.resolveColor(themedCtx, R.attr.mzCardSoft);
        int text = ThemeManager.resolveColor(themedCtx, R.attr.mzText);
        int muted = ThemeManager.resolveColor(themedCtx, R.attr.mzMuted);
        int accent = ThemeManager.resolveColor(themedCtx, R.attr.mzPrimary);

        int cardAlt = ColorUtils.blendARGB(card, bg, 0.35f);
        int subtle = ColorUtils.blendARGB(muted, text, 0.2f);
        int accentText = accent;

        snap.colorBg = bg;
        snap.colorCard = card;
        snap.colorCardAlt = cardAlt;
        snap.colorText = text;
        snap.colorMuted = muted;
        snap.colorSubtle = subtle;
        snap.colorAccent = accent;
        snap.colorAccentText = accentText;
    }

    private static boolean ensureSessionFromPrefs(Context ctx) {
        MzutSession.initializeFromPreferences(ctx);
        MzutSession s = MzutSession.getInstance();
        return s.getUserId() != null && s.getAuthKey() != null;
    }

    private static LocalDate findBestDateToShow(PlanRepository.PlanResult weekResult, LocalDate today, int nowMin,
            Set<String> hiddenKeys) {
        List<PlanRepository.PlanEventUi> todayEvents = getEventsForDate(weekResult, today, hiddenKeys);
        for (PlanRepository.PlanEventUi ev : todayEvents) {
            if (ev.endMin > nowMin)
                return today;
        }

        for (int i = 1; i <= 7; i++) {
            LocalDate d = today.plusDays(i);
            List<PlanRepository.PlanEventUi> dEvents = getEventsForDate(weekResult, d, hiddenKeys);
            if (!dEvents.isEmpty())
                return d;
        }

        return today.plusDays(1);
    }

    private static List<PlanRepository.PlanEventUi> getEventsForDate(PlanRepository.PlanResult result,
            LocalDate date, Set<String> hiddenKeys) {
        if (result == null || result.dayColumns == null)
            return Collections.emptyList();

        for (PlanRepository.DayColumn col : result.dayColumns) {
            if (date.equals(col.date) && col.events != null) {
                List<PlanRepository.PlanEventUi> out = new ArrayList<>();
                for (PlanRepository.PlanEventUi ev : col.events) {
                    if (ev.subjectKey != null && hiddenKeys.contains(ev.subjectKey))
                        continue;
                    out.add(ev);
                }
                Collections.sort(out, (a, b) -> Integer.compare(a.startMin, b.startMin));
                return out;
            }
        }
        return Collections.emptyList();
    }
}
