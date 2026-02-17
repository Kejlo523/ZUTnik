package pl.kejlo.mzutv2.wear;

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

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("dateIso", dateIso != null ? dateIso : "");
            root.put("dateLabel", dateLabel != null ? dateLabel : "");
            root.put("subtitle", subtitle != null ? subtitle : "");
            root.put("refreshedLabel", refreshedLabel != null ? refreshedLabel : "");
            root.put("languageTag", languageTag != null ? languageTag : "");
            root.put("loginRequired", loginRequired);
            root.put("theme", theme != null ? theme : "");
            root.put("colorBg", colorBg);
            root.put("colorCard", colorCard);
            root.put("colorCardAlt", colorCardAlt);
            root.put("colorText", colorText);
            root.put("colorMuted", colorMuted);
            root.put("colorSubtle", colorSubtle);
            root.put("colorAccent", colorAccent);
            root.put("colorAccentText", colorAccentText);

            JSONArray arr = new JSONArray();
            for (Event e : events) {
                JSONObject o = new JSONObject();
                o.put("title", e.title != null ? e.title : "");
                o.put("time", e.time != null ? e.time : "");
                o.put("room", e.room != null ? e.room : "");
                o.put("color", e.color);
                arr.put(o);
            }
            root.put("events", arr);

            JSONArray weekArr = new JSONArray();
            for (WeekDay day : weekDays) {
                JSONObject d = new JSONObject();
                d.put("dateIso", day.dateIso != null ? day.dateIso : "");
                d.put("dateLabel", day.dateLabel != null ? day.dateLabel : "");
                JSONArray dayEvents = new JSONArray();
                for (Event e : day.events) {
                    JSONObject o = new JSONObject();
                    o.put("title", e.title != null ? e.title : "");
                    o.put("time", e.time != null ? e.time : "");
                    o.put("room", e.room != null ? e.room : "");
                    o.put("color", e.color);
                    dayEvents.put(o);
                }
                d.put("events", dayEvents);
                weekArr.put(d);
            }
            root.put("week", weekArr);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
