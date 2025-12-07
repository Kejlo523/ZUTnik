package pl.kejlo.mzutv2;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

public class Tile {
    public long id;
    public int col;
    public int row;
    public int colSpan;
    public int rowSpan;
    public String title;
    public String description;

    // Action types
    public static final String ACTION_PLAN = "plan";
    public static final String ACTION_GRADES = "grades";
    public static final String ACTION_INFO = "info";
    public static final String ACTION_NEWS = "news";
    public static final String ACTION_ACTIVITY = "activity"; // Generic activity
    public static final String ACTION_URL = "url";
    public static final String ACTION_PLAN_SEARCH = "plan_search";
    public static final String ACTION_NEWS_LATEST = "news_latest";

    public String actionType;
    public String actionData; // e.g. class name or url
    public int color = 0; // 0 = default

    public Tile() {
    }

    public Tile(long id, int col, int row, int colSpan, int rowSpan, String title, String description, String actionType, @Nullable String actionData) {
        this(id, col, row, colSpan, rowSpan, title, description, actionType, actionData, 0);
    }

    public Tile(long id, int col, int row, int colSpan, int rowSpan, String title, String description, String actionType, @Nullable String actionData, int color) {
        this.id = id;
        this.col = col;
        this.row = row;
        this.colSpan = colSpan;
        this.rowSpan = rowSpan;
        this.title = title;
        this.description = description;
        this.actionType = actionType;
        this.actionData = actionData;
        this.color = color;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("col", col);
        json.put("row", row);
        json.put("colSpan", colSpan);
        json.put("rowSpan", rowSpan);
        json.put("title", title);
        json.put("description", description);
        json.put("actionType", actionType);
        if (actionData != null) {
            json.put("actionData", actionData);
        }
        json.put("color", color);
        return json;
    }

    public static Tile fromJson(JSONObject json) {
        Tile t = new Tile();
        t.id = json.optLong("id");
        t.col = json.optInt("col");
        t.row = json.optInt("row");
        t.colSpan = json.optInt("colSpan", 1);
        t.rowSpan = json.optInt("rowSpan", 1);
        t.title = json.optString("title", "");
        t.description = json.optString("description", "");
        t.actionType = json.optString("actionType", "");
        t.actionData = json.optString("actionData", null);
        t.color = json.optInt("color", 0);
        return t;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tile tile = (Tile) o;
        return id == tile.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
