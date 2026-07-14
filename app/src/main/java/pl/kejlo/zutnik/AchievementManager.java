package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AchievementManager {

    private static final String PREFS_NAME = "zutnik_achievements";
    private static final String LEGACY_PREFS_NAME = "about_easter_egg";
    private static final String LEGACY_DISCOVERED_AT = "discovered_at";
    private static final String LEGACY_DISCOVERY_CODE = "discovery_code";
    private static final String KEY_MIGRATED_LEGACY = "migrated_about_easter_egg";
    private static final String KEY_UNLOCKED_AT_PREFIX = "unlocked_at_";
    private static final String KEY_CODE_PREFIX = "code_";

    enum Achievement {
        EXPLORER(
                "explorer",
                "ZN",
                R.string.achievement_explorer_title,
                R.string.achievement_explorer_description,
                R.string.achievement_explorer_hint,
                R.drawable.ic_achievement_explorer,
                false),
        OWN_ALBUM(
                "own_album",
                "JA",
                R.string.achievement_own_album_title,
                R.string.achievement_own_album_description,
                R.string.achievement_own_album_hint,
                R.drawable.ic_achievement_own_album,
                false),
        NIGHT_PLAN(
                "night_plan",
                "ND",
                R.string.achievement_night_plan_title,
                R.string.achievement_night_plan_description,
                R.string.achievement_night_plan_hint,
                R.drawable.ic_achievement_night_plan,
                false),
        OFFLINE_PLAN(
                "offline_plan",
                "PZ",
                R.string.achievement_offline_plan_title,
                R.string.achievement_offline_plan_description,
                R.string.achievement_offline_plan_hint,
                R.drawable.ic_achievement_offline_plan,
                false),
        TIMEKEEPER(
                "timekeeper",
                "WP",
                R.string.achievement_timekeeper_title,
                R.string.achievement_timekeeper_description,
                R.string.achievement_timekeeper_hint,
                R.drawable.ic_achievement_timekeeper,
                false),
        FULL_VIEW(
                "full_view",
                "PO",
                R.string.achievement_full_view_title,
                R.string.achievement_full_view_description,
                R.string.achievement_full_view_hint,
                R.drawable.ic_achievement_full_view,
                false);

        final String id;
        final String codePrefix;
        @StringRes final int titleRes;
        @StringRes final int descriptionRes;
        @StringRes final int hintRes;
        @DrawableRes final int iconRes;
        final boolean tintIcon;

        Achievement(
                String id,
                String codePrefix,
                @StringRes int titleRes,
                @StringRes int descriptionRes,
                @StringRes int hintRes,
                @DrawableRes int iconRes,
                boolean tintIcon) {
            this.id = id;
            this.codePrefix = codePrefix;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.hintRes = hintRes;
            this.iconRes = iconRes;
            this.tintIcon = tintIcon;
        }
    }

    static final class Record {
        final Achievement achievement;
        final long unlockedAt;
        final String code;

        Record(Achievement achievement, long unlockedAt, String code) {
            this.achievement = achievement;
            this.unlockedAt = unlockedAt;
            this.code = code;
        }

        boolean isUnlocked() {
            return unlockedAt > 0L;
        }
    }

    static final class UnlockResult {
        final Record record;
        final boolean newlyUnlocked;

        UnlockResult(Record record, boolean newlyUnlocked) {
            this.record = record;
            this.newlyUnlocked = newlyUnlocked;
        }
    }

    private AchievementManager() {
    }

    static synchronized UnlockResult unlock(Context context, Achievement achievement) {
        migrateLegacyAchievement(context);
        SharedPreferences preferences = preferences(context);
        Record existing = readRecord(preferences, achievement);
        if (existing.isUnlocked()) {
            return new UnlockResult(existing, false);
        }

        long unlockedAt = System.currentTimeMillis();
        String code = createCode(achievement.codePrefix);
        preferences.edit()
                .putLong(unlockedAtKey(achievement), unlockedAt)
                .putString(codeKey(achievement), code)
                .apply();
        return new UnlockResult(new Record(achievement, unlockedAt, code), true);
    }

    static List<Record> getAll(Context context) {
        migrateLegacyAchievement(context);
        SharedPreferences preferences = preferences(context);
        List<Record> records = new ArrayList<>(Achievement.values().length);
        for (Achievement achievement : Achievement.values()) {
            records.add(readRecord(preferences, achievement));
        }
        return records;
    }

    static boolean isOwnAlbumSearch(String category, String query, String ownStudentNumber) {
        if (!"album".equals(category)) {
            return false;
        }
        String normalizedQuery = normalizeAlbumNumber(query);
        String normalizedOwnNumber = normalizeAlbumNumber(ownStudentNumber);
        return !normalizedOwnNumber.isEmpty() && normalizedOwnNumber.equals(normalizedQuery);
    }

    static String normalizeAlbumNumber(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    static boolean isNightPlanVisit(LocalTime time) {
        return time != null && time.getHour() < 5;
    }

    static boolean hasViewedAllPlanModes(Set<String> viewedModes) {
        return viewedModes != null
                && viewedModes.contains("day")
                && viewedModes.contains("week")
                && viewedModes.contains("month");
    }

    private static Record readRecord(SharedPreferences preferences, Achievement achievement) {
        long unlockedAt = preferences.getLong(unlockedAtKey(achievement), 0L);
        String code = preferences.getString(codeKey(achievement), "");
        return new Record(achievement, unlockedAt, code != null ? code : "");
    }

    private static synchronized void migrateLegacyAchievement(Context context) {
        SharedPreferences target = preferences(context);
        if (target.getBoolean(KEY_MIGRATED_LEGACY, false)) {
            return;
        }

        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
        long discoveredAt = legacy.getLong(LEGACY_DISCOVERED_AT, 0L);
        String code = legacy.getString(LEGACY_DISCOVERY_CODE, "");

        SharedPreferences.Editor editor = target.edit().putBoolean(KEY_MIGRATED_LEGACY, true);
        if (discoveredAt > 0L) {
            editor.putLong(unlockedAtKey(Achievement.EXPLORER), discoveredAt);
            editor.putString(
                    codeKey(Achievement.EXPLORER),
                    code == null || code.isEmpty() ? createCode(Achievement.EXPLORER.codePrefix) : code);
        }
        editor.apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String unlockedAtKey(Achievement achievement) {
        return KEY_UNLOCKED_AT_PREFIX + achievement.id;
    }

    private static String codeKey(Achievement achievement) {
        return KEY_CODE_PREFIX + achievement.id;
    }

    private static String createCode(String prefix) {
        return String.format(Locale.ROOT, "%s-%04X", prefix, new SecureRandom().nextInt(0x10000));
    }
}
