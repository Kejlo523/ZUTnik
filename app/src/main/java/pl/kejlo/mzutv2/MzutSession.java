package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Central user session.
 *
 * - Keeps session data in RAM
 * - Can load/save itself to SharedPreferences
 * - After process kill we still have:
 *   - userId
 *   - authKey
 *   - username
 *   - imageUrl
 *   - activeStudyIndex
 *
 * Notes:
 * - In Activities / Services / Widgets prefer calling getInstance(context)
 * - The old getInstance() remains, but should be used less over time
 */
public final class MzutSession {

    // region Singleton

    private static MzutSession instance;

    /**
     * Old version – does NOT load from SharedPreferences.
     * Safe only if getInstance(context) / initializeFromPreferences(context)
     * has been called earlier.
     */
    public static synchronized MzutSession getInstance() {
        if (instance == null) {
            instance = new MzutSession();
        }
        return instance;
    }

    /**
     * Preferred method – loads session from SharedPreferences if the singleton
     * does not exist yet.
     */
    public static synchronized MzutSession getInstance(Context context) {
        if (instance == null) {
            instance = new MzutSession();
            instance.loadFromPreferences(context);
        }
        return instance;
    }

    /**
     * Can be called e.g. in onCreate() of HomeActivity / PlanActivity / widget service
     * when we only want to ensure that data from SharedPreferences is loaded.
     */
    public static synchronized void initializeFromPreferences(Context context) {
        if (instance == null) {
            instance = new MzutSession();
        }
        instance.loadFromPreferences(context);
        ImageCache.init(context);
    }

    /**
     * Clears in-memory session and stored data in SharedPreferences.
     * Useful on logout.
     */
    public static synchronized void clear(Context context) {
        // Clear singleton
        instance = null;

        // Clear SharedPreferences
        SharedPreferences prefs = getPreferences(context);
        prefs.edit().clear().apply();
    }

    // endregion

    // region SharedPreferences

    private static final String PREFS_NAME = "mzut_prefs";

    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_AUTH_KEY = "auth_key";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_IMAGE_URL = "image_url";
    private static final String KEY_ACTIVE_STUDY_INDEX = "active_study_idx";
    private static final String KEY_STUDIES_JSON = "studies_json";

    /**
     * Returns SharedPreferences used by the session.
     * Always uses application context to avoid leaking Activity.
     */
    private static SharedPreferences getPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // endregion

    // region Session data in RAM

    // User data
    private String userId;
    private String username;
    private String authKey;
    private String imageUrl;

    // Studies data (similar to $_SESSION['STUDIES'], ACTIVE_STUDY_IDX)
    // Not persisted in SharedPreferences – kept only in RAM.
    private List<Study> studies;
    private int activeStudyIndex = 0;

    // endregion

    // region Construction

    private MzutSession() {
        // Real initialization is done via loadFromPreferences()
    }

    // endregion

    // region Public API – loading / saving

    /**
     * Loads session data from SharedPreferences into this instance.
     * Does not create a new singleton – uses the current one (this).
     */
    private void loadFromPreferences(Context context) {
        SharedPreferences prefs = getPreferences(context);

        this.userId = prefs.getString(KEY_USER_ID, null);
        this.authKey = prefs.getString(KEY_AUTH_KEY, null);
        this.username = prefs.getString(KEY_USERNAME, null);
        this.imageUrl = prefs.getString(KEY_IMAGE_URL, null);
        this.activeStudyIndex = prefs.getInt(KEY_ACTIVE_STUDY_INDEX, 0);

        String studiesJson = prefs.getString(KEY_STUDIES_JSON, null);
        if (studiesJson != null) {
            try {
                JSONArray arr = new JSONArray(studiesJson);
                List<Study> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Study s = new Study();
                    s.przynaleznoscId = o.optString("id", null);
                    s.label = o.optString("lbl", null);
                    list.add(s);
                }
                this.studies = list;
            } catch (JSONException e) {
                // ignore
            }
        }
    }

    /**
     * Saves the current session state to SharedPreferences.
     * Call after login / study change / profile update.
     */
    public void saveToPreferences(Context context) {
        SharedPreferences prefs = getPreferences(context);

        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_USER_ID, userId);
        e.putString(KEY_AUTH_KEY, authKey);
        e.putString(KEY_USERNAME, username);
        e.putString(KEY_IMAGE_URL, imageUrl);
        e.putInt(KEY_ACTIVE_STUDY_INDEX, activeStudyIndex);

        if (studies != null && !studies.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Study s : studies) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", s.przynaleznoscId);
                    o.put("lbl", s.label);
                    arr.put(o);
                } catch (JSONException ignored) {}
            }
            e.putString(KEY_STUDIES_JSON, arr.toString());
        } else {
            e.remove(KEY_STUDIES_JSON);
        }

        e.apply();
    }

    /**
     * Convenient method for setting the entire user at once.
     * Used for example in LoginActivity after successful login.
     */
    public void updateUser(String userId, String username, String authKey, String imageUrl) {
        this.userId = userId;
        this.username = username;
        this.authKey = authKey;
        this.imageUrl = imageUrl;
    }

    // endregion

    // region Getters / setters

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAuthKey() {
        return authKey;
    }

    public void setAuthKey(String authKey) {
        this.authKey = authKey;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public List<Study> getStudies() {
        return studies;
    }

    public void setStudies(List<Study> studies) {
        this.studies = studies;
    }

    public int getActiveStudyIndex() {
        return activeStudyIndex;
    }

    public void setActiveStudyIndex(int activeStudyIndex) {
        this.activeStudyIndex = activeStudyIndex;
    }

    // endregion
}
