package pl.kejlo.mzutv2.wear.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;

public final class WearScheduleUtils {

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");

    private WearScheduleUtils() {}

    public static final class NextEventInfo {
        public final WearPlanSnapshot.Event event;
        public final ZonedDateTime start;
        public final String dayLabel;
        public final String timeRange;

        public NextEventInfo(WearPlanSnapshot.Event event, ZonedDateTime start,
                String dayLabel, String timeRange) {
            this.event = event;
            this.start = start;
            this.dayLabel = dayLabel != null ? dayLabel : "";
            this.timeRange = timeRange != null ? timeRange : "";
        }
    }

    public static NextEventInfo findNextEvent(WearPlanSnapshot snap, ZonedDateTime now) {
        if (snap == null) {
            return null;
        }
        ZoneId zone = now.getZone();
        NextEventInfo best = null;
        if (snap.weekDays != null && !snap.weekDays.isEmpty()) {
            for (WearPlanSnapshot.WeekDay day : snap.weekDays) {
                if (day == null || day.events == null || day.events.isEmpty()) {
                    continue;
                }
                LocalDate date = parseDate(day.dateIso);
                if (date == null) {
                    continue;
                }
                for (WearPlanSnapshot.Event ev : day.events) {
                    LocalTime startTime = parseStartTime(ev != null ? ev.time : "");
                    if (startTime == null) {
                        continue;
                    }
                    ZonedDateTime start = ZonedDateTime.of(date, startTime, zone);
                    if (start.isBefore(now.minusMinutes(1))) {
                        continue;
                    }
                    if (best == null || start.isBefore(best.start)) {
                        best = new NextEventInfo(ev, start, day.dateLabel, ev != null ? ev.time : "");
                    }
                }
            }
        }
        if (best == null && snap.events != null && !snap.events.isEmpty()) {
            LocalDate date = parseDate(snap.dateIso);
            if (date != null) {
                for (WearPlanSnapshot.Event ev : snap.events) {
                    LocalTime startTime = parseStartTime(ev != null ? ev.time : "");
                    if (startTime == null) {
                        continue;
                    }
                    ZonedDateTime start = ZonedDateTime.of(date, startTime, zone);
                    if (start.isBefore(now.minusMinutes(1))) {
                        continue;
                    }
                    if (best == null || start.isBefore(best.start)) {
                        best = new NextEventInfo(ev, start, snap.dateLabel, ev != null ? ev.time : "");
                    }
                }
            }
        }
        return best;
    }

    public static String formatEta(ZonedDateTime now, ZonedDateTime start) {
        if (now == null || start == null) {
            return "";
        }
        long minutes = Duration.between(now, start).toMinutes();
        if (minutes <= 0) {
            return "teraz";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        if (h <= 0) {
            return "za " + m + "m";
        }
        if (m == 0) {
            return "za " + h + "h";
        }
        return "za " + h + "h " + m + "m";
    }

    public static String formatEtaShort(ZonedDateTime now, ZonedDateTime start) {
        if (now == null || start == null) {
            return "";
        }
        long minutes = Duration.between(now, start).toMinutes();
        if (minutes <= 0) {
            return "teraz";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        if (h <= 0) {
            return "za " + m + "m";
        }
        return "za " + h + "h";
    }

    public static String ellipsize(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static LocalDate parseDate(String dateIso) {
        if (dateIso == null || dateIso.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateIso);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalTime parseStartTime(String timeRange) {
        if (timeRange == null) {
            return null;
        }
        Matcher m = TIME_PATTERN.matcher(timeRange);
        if (!m.find()) {
            return null;
        }
        try {
            int hour = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (hour < 0 || hour > 23 || min < 0 || min > 59) {
                return null;
            }
            return LocalTime.of(hour, min);
        } catch (Exception ignored) {
            return null;
        }
    }
}
