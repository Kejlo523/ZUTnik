package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HomeRepository {

    private static final String PREF_NAME = "mzut_home_prefs";
    private static final String KEY_TILES = "tiles_config";

    private final Context context;

    public HomeRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public void saveTiles(List<Tile> tiles) {
        JSONArray arr = new JSONArray();
        for (Tile t : tiles) {
            try {
                arr.put(t.toJson());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TILES, arr.toString()).apply();
    }

    public List<Tile> loadTiles() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String jsonStr = prefs.getString(KEY_TILES, null);

        if (jsonStr == null) {
            return createDefaultTiles();
        }

        try {
            List<Tile> list = new ArrayList<>();
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Tile t = Tile.fromJson(obj);

                // MIGRATION: Restore resource IDs for default tiles if missing
                // Only if the current text matches one of the known defaults (to avoid
                // overwriting custom user titles)
                if (t.titleResId == 0 && !obj.has("titleResId")) {
                    boolean matchesDefault = isDefaultTitleOrDesc((int) t.id, t.title, t.description);
                    if (matchesDefault) {
                        switch ((int) t.id) {
                            case 1:
                                t.titleResId = R.string.home_tile_plan_title;
                                t.descResId = R.string.home_tile_plan_desc;
                                break;
                            case 2:
                                t.titleResId = R.string.home_tile_grades_title;
                                t.descResId = R.string.home_tile_grades_desc;
                                break;
                            case 3:
                                t.titleResId = R.string.home_tile_info_title;
                                t.descResId = R.string.home_tile_info_desc;
                                break;
                            case 4:
                                t.titleResId = R.string.home_tile_news_title;
                                t.descResId = R.string.home_tile_news_desc;
                                break;
                        }
                    }
                }
                list.add(t);
            }
            return list;
        } catch (JSONException e) {
            e.printStackTrace();
            return createDefaultTiles();
        }
    }

    private boolean isDefaultTitleOrDesc(int id, String title, String desc) {
        // Check against known English and Polish defaults
        // This prevents overwriting user customizations during migration
        switch (id) {
            case 1: // Plan
                return isMatch(title, "Plan zajęć", "Timetable") || isMatch(desc, "Widok dnia", "Day, week");
            case 2: // Grades
                return isMatch(title, "Oceny", "Grades") || isMatch(desc, "Średnia", "Average");
            case 3: // Info
                return isMatch(title, "Informacje o studiach", "Study information")
                        || isMatch(desc, "Kierunek", "Field of study");
            case 4: // News
                return isMatch(title, "Aktualności ZUT", "ZUT news") || isMatch(desc, "Komunikaty", "Announcements");
        }
        return false;
    }

    private boolean isMatch(String value, String... defaults) {
        if (value == null)
            return false;
        for (String def : defaults) {
            if (value.trim().equalsIgnoreCase(def) || value.contains(def))
                return true;
        }
        return false;
    }

    public List<Tile> createDefaultTiles() {
        List<Tile> list = new ArrayList<>();
        // 4 column grid

        // Plan: 2x1 at 0,0
        Tile planTile = new Tile(
                1, 0, 0, 2, 2,
                context.getString(R.string.home_tile_plan_title),
                context.getString(R.string.home_tile_plan_desc),
                Tile.ACTION_PLAN, null);
        planTile.titleResId = R.string.home_tile_plan_title;
        planTile.descResId = R.string.home_tile_plan_desc;
        list.add(planTile);

        // Grades: 2x2 at 2,0
        Tile gradesTile = new Tile(
                2, 2, 0, 2, 2,
                context.getString(R.string.home_tile_grades_title),
                context.getString(R.string.home_tile_grades_desc),
                Tile.ACTION_GRADES, null);
        gradesTile.titleResId = R.string.home_tile_grades_title;
        gradesTile.descResId = R.string.home_tile_grades_desc;
        list.add(gradesTile);

        // Info: 2x2 at 0,2
        Tile infoTile = new Tile(
                3, 0, 2, 2, 2,
                context.getString(R.string.home_tile_info_title),
                context.getString(R.string.home_tile_info_desc),
                Tile.ACTION_INFO, null);
        infoTile.titleResId = R.string.home_tile_info_title;
        infoTile.descResId = R.string.home_tile_info_desc;
        list.add(infoTile);

        // News: 2x2 at 2,2
        Tile newsTile = new Tile(
                4, 2, 2, 2, 2,
                context.getString(R.string.home_tile_news_title),
                context.getString(R.string.home_tile_news_desc),
                Tile.ACTION_NEWS, null);
        newsTile.titleResId = R.string.home_tile_news_title;
        newsTile.descResId = R.string.home_tile_news_desc;
        list.add(newsTile);

        return list;
    }

    public List<Tile> restoreDefaults() {
        List<Tile> defaults = createDefaultTiles();
        saveTiles(defaults);
        return defaults;
    }
}
