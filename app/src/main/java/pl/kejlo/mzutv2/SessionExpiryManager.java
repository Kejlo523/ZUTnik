package pl.kejlo.mzutv2;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionExpiryManager {

    private static final String TAG = "mZUTv2-SessionExpiry";
    private static final AtomicBoolean HANDLING_EXPIRE = new AtomicBoolean(false);

    private static final String PREFS_RUNTIME = "mzut_runtime_flags";
    private static final String KEY_NOTICE_PENDING = "session_expired_notice_pending";

    private SessionExpiryManager() {
    }

    public static boolean isSessionExpiredResponse(JSONObject response) {
        if (response == null || !hasActiveSession()) {
            return false;
        }

        String loginStatus = firstNonEmpty(
                response.optString("logInStatus", ""),
                response.optString("loginInStatus", ""));
        if (!loginStatus.isEmpty() && !"OK".equalsIgnoreCase(loginStatus)) {
            return true;
        }

        String[] candidates = new String[] {
                response.optString("status", ""),
                response.optString("Status", ""),
                response.optString("message", ""),
                response.optString("komunikat", ""),
                response.optString("error", ""),
                response.optString("blad", ""),
                response.optString("msg", ""),
                response.optString("opis", ""),
                response.optString("description", "")
        };

        for (String raw : candidates) {
            if (looksLikeSessionExpired(raw)) {
                return true;
            }
        }
        return false;
    }

    public static String extractSessionExpiredReason(JSONObject response) {
        if (response == null) {
            return "";
        }
        return firstNonEmpty(
                response.optString("message", ""),
                response.optString("komunikat", ""),
                response.optString("error", ""),
                response.optString("status", ""),
                response.optString("Status", ""),
                response.optString("msg", ""));
    }

    public static void handleSessionExpired(Context context, String reason) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();

        if (!hasActiveSession()) {
            return;
        }
        if (!HANDLING_EXPIRE.compareAndSet(false, true)) {
            return;
        }

        try {
            markNoticePending(appContext, true);
            NotificationSyncManager.cancelWorker(appContext);
            MzutSession.clearSessionData(appContext);

            if (isAppInForeground(appContext)) {
                showToastAndOpenLogin(appContext);
            } else {
                postSessionExpiredNotification(appContext);
            }
            Log.w(TAG, "Session expired. Reason: " + reason);
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle session expiration", e);
        } finally {
            HANDLING_EXPIRE.set(false);
        }
    }

    public static boolean consumeSessionExpiredNotice(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE);
        boolean pending = prefs.getBoolean(KEY_NOTICE_PENDING, false);
        if (pending) {
            prefs.edit().putBoolean(KEY_NOTICE_PENDING, false).apply();
        }
        return pending;
    }

    public static void clearSessionExpiredNotice(Context context) {
        if (context == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTICE_PENDING, false)
                .apply();
    }

    private static void markNoticePending(Context context, boolean pending) {
        context.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTICE_PENDING, pending)
                .apply();
    }

    private static void showToastAndOpenLogin(Context context) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            Intent intent = new Intent(context, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(LoginActivity.EXTRA_SESSION_EXPIRED, true);
            context.startActivity(intent);
        });
    }

    private static void postSessionExpiredNotification(Context context) {
        if (!NotificationSyncManager.hasNotificationPermission(context)) {
            return;
        }

        NotificationSyncManager.ensureChannels(context);

        Intent openIntent = new Intent(context, LoginActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        openIntent.putExtra(LoginActivity.EXTRA_SESSION_EXPIRED, true);

        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                8801,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                NotificationSyncManager.CHANNEL_AUTH)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(context.getString(R.string.session_expired_notification_title))
                .setContentText(context.getString(R.string.session_expired_notification_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.session_expired_notification_text)))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat.from(context).notify(8801, builder.build());
    }

    private static boolean hasActiveSession() {
        MzutSession session = MzutSession.getInstance();
        return session.getUserId() != null && session.getAuthKey() != null;
    }

    private static boolean isAppInForeground(Context context) {
        ActivityManager.RunningAppProcessInfo proc = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(proc);
        return proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                || proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private static boolean looksLikeSessionExpired(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }

        String n = normalize(input);
        if (n.contains("session expired") || n.contains("token expired") || n.contains("unauthorized")) {
            return true;
        }
        if ((n.contains("sesja") || n.contains("session")) && (n.contains("wygasl") || n.contains("expired"))) {
            return true;
        }
        if (n.contains("token") && (
                n.contains("wygasl")
                        || n.contains("expired")
                        || n.contains("invalid")
                        || n.contains("niepopraw")
                        || n.contains("niewazn")
                        || n.contains("bled"))) {
            return true;
        }
        if (n.contains("autoryz") && (n.contains("brak") || n.contains("niepopraw"))) {
            return true;
        }
        return false;
    }

    private static String normalize(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = s.replaceAll("\\p{M}+", "");
        return s;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
