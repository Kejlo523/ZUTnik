package pl.kejlo.zutnik;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SettingsBackupManager {

    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> EXPORTED_SETTINGS = new HashSet<>(Arrays.asList(
            SettingsPrefs.KEY_APP_LANGUAGE,
            SettingsPrefs.KEY_APP_THEME,
            SettingsPrefs.KEY_WIDGET_REFRESH_INTERVAL,
            SettingsPrefs.KEY_NOTIFICATIONS_PERMISSION_ASKED,
            SettingsPrefs.KEY_NOTIFICATIONS_MASTER_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_GRADES_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_DUE_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_FINANCE_BOOKED_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_PLAN_MOVED_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_PLAN_CANCELLED_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_PLAN_ADDED_ENABLED,
            SettingsPrefs.KEY_NOTIFICATIONS_PLAN_REMOVED_ENABLED));

    private SettingsBackupManager() {
    }

    public static String exportToJson(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("applicationId", BuildConfig.APPLICATION_ID);
        root.put("createdAt", System.currentTimeMillis());

        SharedPreferences preferences = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (EXPORTED_SETTINGS.contains(entry.getKey())) {
                settings.put(entry.getKey(), entry.getValue());
            }
        }
        root.put("settings", settings);

        JSONArray tiles = new JSONArray();
        for (Tile tile : new HomeRepository(context).loadTiles()) {
            tiles.put(tile.toJson());
        }
        root.put("tiles", tiles);

        JSONArray searches = new JSONArray();
        for (PlanRepository.SavedSearch search : PlanRepository.loadSavedSearches(context)) {
            JSONObject item = new JSONObject();
            item.put("label", search.label);
            item.put("category", search.catKey);
            item.put("categoryLabel", search.catLabel);
            item.put("query", search.query);
            searches.put(item);
        }
        root.put("savedSearches", searches);
        return root.toString(2);
    }

    public static void importFromJson(Context context, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema", -1) != SCHEMA_VERSION
                || !BuildConfig.APPLICATION_ID.equals(root.optString("applicationId", ""))) {
            throw new IllegalArgumentException("Unsupported settings backup");
        }

        JSONObject settings = root.optJSONObject("settings");
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsPrefs.PREFS_SETTINGS,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : EXPORTED_SETTINGS) {
            editor.remove(key);
        }
        if (settings != null) {
            for (String key : EXPORTED_SETTINGS) {
                if (!settings.has(key)) continue;
                Object value = settings.opt(key);
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Number) {
                    editor.putLong(key, ((Number) value).longValue());
                } else if (value != null && value != JSONObject.NULL) {
                    editor.putString(key, String.valueOf(value));
                }
            }
        }
        editor.apply();

        JSONArray tileArray = root.optJSONArray("tiles");
        if (tileArray != null) {
            List<Tile> tiles = new ArrayList<>();
            for (int i = 0; i < tileArray.length(); i++) {
                JSONObject item = tileArray.optJSONObject(i);
                if (item != null) {
                    Tile tile = Tile.fromJson(item);
                    tile.colSpan = Math.max(1, Math.min(4, tile.colSpan));
                    tile.rowSpan = Math.max(1, Math.min(6, tile.rowSpan));
                    tiles.add(tile);
                }
            }
            if (!tiles.isEmpty()) {
                new HomeRepository(context).saveTiles(tiles);
            }
        }

        JSONArray searchArray = root.optJSONArray("savedSearches");
        if (searchArray != null) {
            List<PlanRepository.SavedSearch> searches = new ArrayList<>();
            for (int i = 0; i < searchArray.length(); i++) {
                JSONObject item = searchArray.optJSONObject(i);
                if (item == null) continue;
                String category = item.optString("category", "").trim();
                String query = item.optString("query", "").trim();
                if (!category.isEmpty() && !query.isEmpty()) {
                    searches.add(new PlanRepository.SavedSearch(
                            item.optString("label", query),
                            category,
                            item.optString("categoryLabel", category),
                            query));
                }
            }
            PlanRepository.saveSavedSearches(context, searches);
        }

        PlanDayWidgetProvider.rescheduleRefresh(context);
        NotificationSyncManager.syncWorkerSchedule(context);
    }
}
