package pl.kejlo.mzutv2;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class NotificationDebugTools {

    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM", Locale.getDefault());

    public enum PlanChangeType {
        ADDED,
        CANCELLED,
        MOVED
    }

    private NotificationDebugTools() {
    }

    public static void showTestGradesNotification(Context context) {
        if (context == null) {
            return;
        }
        showSimulatedGradeNotification(
                context,
                context.getString(R.string.settings_debug_default_subject),
                context.getString(R.string.settings_debug_default_grade_value),
                LocalDate.now());
    }

    public static void showTestPlanNotification(Context context) {
        if (context == null) {
            return;
        }
        showSimulatedPlanNotification(
                context,
                PlanChangeType.ADDED,
                context.getString(R.string.settings_debug_default_subject),
                LocalDate.now(),
                8 * 60,
                null,
                0);
    }

    public static void showSimulatedGradeNotification(
            Context context,
            String subject,
            String gradeValue,
            LocalDate date) {
        if (context == null || !NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationSyncManager.ensureChannels(appContext);

        String safeSubject = safeOrDefault(subject, appContext.getString(R.string.settings_debug_default_subject));
        String safeGradeValue = safeOrDefault(
                gradeValue,
                appContext.getString(R.string.settings_debug_default_grade_value));
        LocalDate safeDate = date != null ? date : LocalDate.now();
        String line = appContext.getString(
                R.string.notif_debug_grade_line,
                safeSubject,
                safeGradeValue,
                formatDate(safeDate));

        Intent openIntent = new Intent(appContext, GradesActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                appContext,
                99011,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext,
                NotificationSyncManager.CHANNEL_GRADES)
                .setSmallIcon(R.drawable.ic_school)
                .setContentTitle(appContext.getString(R.string.notif_debug_grades_title))
                .setContentText(line)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(line))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi);

        NotificationManagerCompat.from(appContext).notify(9901, builder.build());
    }

    public static void showSimulatedPlanNotification(
            Context context,
            PlanChangeType changeType,
            String subject,
            LocalDate fromDate,
            int fromStartMinutes,
            LocalDate toDate,
            int toStartMinutes) {
        if (context == null || !NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        NotificationSyncManager.ensureChannels(appContext);

        PlanChangeType safeType = changeType != null ? changeType : PlanChangeType.ADDED;
        String safeSubject = safeOrDefault(subject, appContext.getString(R.string.settings_debug_default_subject));
        LocalDate safeFromDate = fromDate != null ? fromDate : LocalDate.now();
        int safeFromStartMinutes = normalizeMinutes(fromStartMinutes);
        LocalDate safeToDate = toDate != null ? toDate : safeFromDate;
        int safeToStartMinutes = normalizeMinutes(toStartMinutes);

        if (safeType != PlanChangeType.MOVED) {
            safeToDate = safeFromDate;
            safeToStartMinutes = safeFromStartMinutes;
        }

        String line = buildPlanLine(
                appContext,
                safeType,
                safeSubject,
                safeFromDate,
                safeFromStartMinutes,
                safeToDate,
                safeToStartMinutes);

        Intent openIntent = new Intent(appContext, PlanActivity.class);
        openIntent.putExtra("viewMode", "week");
        PendingIntent pi = PendingIntent.getActivity(
                appContext,
                99021,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext,
                NotificationSyncManager.CHANNEL_PLAN)
                .setSmallIcon(R.drawable.ic_calendar)
                .setContentTitle(appContext.getString(R.string.notif_debug_plan_title))
                .setContentText(line)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(line))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi);

        NotificationManagerCompat.from(appContext).notify(9902, builder.build());
    }

    private static String buildPlanLine(
            Context context,
            PlanChangeType type,
            String subject,
            LocalDate fromDate,
            int fromStartMinutes,
            LocalDate toDate,
            int toStartMinutes) {
        if (type == PlanChangeType.CANCELLED) {
            return context.getString(
                    R.string.notif_plan_line_cancelled,
                    subject,
                    formatDate(fromDate),
                    formatTime(fromStartMinutes));
        }
        if (type == PlanChangeType.MOVED) {
            return context.getString(
                    R.string.notif_plan_line_moved,
                    subject,
                    formatDate(fromDate),
                    formatTime(fromStartMinutes),
                    formatDate(toDate),
                    formatTime(toStartMinutes));
        }
        return context.getString(
                R.string.notif_plan_line_added,
                subject,
                formatDate(fromDate),
                formatTime(fromStartMinutes));
    }

    private static String formatDate(LocalDate date) {
        LocalDate safeDate = date != null ? date : LocalDate.now();
        return safeDate.format(DATE_SHORT);
    }

    private static String formatTime(int minutes) {
        int safe = normalizeMinutes(minutes);
        int h = safe / 60;
        int m = safe % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private static int normalizeMinutes(int minutes) {
        if (minutes < 0) {
            return 0;
        }
        if (minutes > (23 * 60 + 59)) {
            return 23 * 60 + 59;
        }
        return minutes;
    }

    private static String safeOrDefault(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
