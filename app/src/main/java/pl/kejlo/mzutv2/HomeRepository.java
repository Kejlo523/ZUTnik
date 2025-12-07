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
                list.add(Tile.fromJson(obj));
            }
            return list;
        } catch (JSONException e) {
            e.printStackTrace();
            return createDefaultTiles();
        }
    }

    public List<Tile> createDefaultTiles() {
        List<Tile> list = new ArrayList<>();
        // 4 column grid
        // Plan: 2x1 at 0,0
        list.add(new Tile(
             1, 0, 0, 2, 2,
             context.getString(R.string.home_tile_plan_title),
             context.getString(R.string.home_tile_plan_desc),
             Tile.ACTION_PLAN, null
        ));

        // Grades: 2x2 at 2,0
        list.add(new Tile(
                2, 2, 0, 2, 2,
                context.getString(R.string.home_tile_grades_title),
                context.getString(R.string.home_tile_grades_desc),
                Tile.ACTION_GRADES, null
        ));

        // Info: 2x2 at 0,2
        list.add(new Tile(
                3, 0, 2, 2, 2,
                context.getString(R.string.home_tile_info_title),
                context.getString(R.string.home_tile_info_desc),
                Tile.ACTION_INFO, null
        ));

        // News: 2x2 at 2,2
        list.add(new Tile(
                4, 2, 2, 2, 2,
                context.getString(R.string.home_tile_news_title),
                context.getString(R.string.home_tile_news_desc),
                Tile.ACTION_NEWS, null
        ));

        return list;
    }
    public List<Tile> restoreDefaults() {
        List<Tile> defaults = createDefaultTiles();
        saveTiles(defaults);
        return defaults;
    }
}
