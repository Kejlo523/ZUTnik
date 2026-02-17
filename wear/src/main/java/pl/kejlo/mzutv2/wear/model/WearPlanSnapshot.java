package pl.kejlo.mzutv2.wear.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WearPlanSnapshot {

    public String dateIso;
    public String dateLabel;
    public String subtitle;
    public String refreshedLabel;
    public String languageTag;
    public boolean loginRequired;
    public String theme;
    public int colorBg;
    public int colorCard;
    public int colorCardAlt;
    public int colorText;
    public int colorMuted;
    public int colorSubtle;
    public int colorAccent;
    public int colorAccentText;
    public final List<Event> events = new ArrayList<>();
    public final List<WeekDay> weekDays = new ArrayList<>();

    public static class Event {
        public String title;
        public String time;
        public String room;
        public int color;
    }

    public static class WeekDay {
        public String dateIso;
        public String dateLabel;
        public final List<Event> events = new ArrayList<>();
    }

    public static WearPlanSnapshot fromJson(String json) {
        WearPlanSnapshot snap = new WearPlanSnapshot();
        if (json == null || json.isEmpty()) {
            return snap;
        }
        try {
            JSONObject root = new JSONObject(json);
            snap.dateIso = root.optString("dateIso", "");
            snap.dateLabel = root.optString("dateLabel", "");
            snap.subtitle = root.optString("subtitle", "");
            snap.refreshedLabel = root.optString("refreshedLabel", "");
            snap.languageTag = root.optString("languageTag", "");
            snap.loginRequired = root.optBoolean("loginRequired", false);
            snap.theme = root.optString("theme", "");
            snap.colorBg = root.optInt("colorBg", 0);
            snap.colorCard = root.optInt("colorCard", 0);
            snap.colorCardAlt = root.optInt("colorCardAlt", 0);
            snap.colorText = root.optInt("colorText", 0);
            snap.colorMuted = root.optInt("colorMuted", 0);
            snap.colorSubtle = root.optInt("colorSubtle", 0);
            snap.colorAccent = root.optInt("colorAccent", 0);
            snap.colorAccentText = root.optInt("colorAccentText", 0);

            JSONArray arr = root.optJSONArray("events");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Event e = new Event();
                    e.title = o.optString("title", "");
                    e.time = o.optString("time", "");
                    e.room = o.optString("room", "");
                    e.color = o.optInt("color", 0xFFFFFFFF);
                    snap.events.add(e);
                }
            }

            JSONArray weekArr = root.optJSONArray("week");
            if (weekArr != null) {
                for (int i = 0; i < weekArr.length(); i++) {
                    JSONObject d = weekArr.optJSONObject(i);
                    if (d == null) {
                        continue;
                    }
                    WeekDay day = new WeekDay();
                    day.dateIso = d.optString("dateIso", "");
                    day.dateLabel = d.optString("dateLabel", "");
                    JSONArray dayEvents = d.optJSONArray("events");
                    if (dayEvents != null) {
                        for (int j = 0; j < dayEvents.length(); j++) {
                            JSONObject o = dayEvents.optJSONObject(j);
                            if (o == null) {
                                continue;
                            }
                            Event e = new Event();
                            e.title = o.optString("title", "");
                            e.time = o.optString("time", "");
                            e.room = o.optString("room", "");
                            e.color = o.optInt("color", 0xFFFFFFFF);
                            day.events.add(e);
                        }
                    }
                    snap.weekDays.add(day);
                }
            }
        } catch (Exception ignored) {
        }
        return snap;
    }
}
