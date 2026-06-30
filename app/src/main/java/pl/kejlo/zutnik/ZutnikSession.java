package pl.kejlo.zutnik;

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
 * - userId
 * - authKey
 * - username
 * - imageUrl
 * - activeStudyIndex
 * - activeStudyId
 *
 * Notes:
 * - In Activities / Services / Widgets prefer calling getInstance(context)
 * - The old getInstance() remains, but should be used less over time
 */
public final class ZutnikSession {

    // region Singleton

    private static ZutnikSession instance;

    private boolean loaded = false;

    /**
     * Old version – does NOT load from SharedPreferences.
     * Safe only if getInstance(context) / initializeFromPreferences(context)
     * has been called earlier.
     */
    public static synchronized ZutnikSession getInstance() {
        if (instance == null) {
            instance = new ZutnikSession();
        }
        return instance;
    }

    /**
     * Preferred method – loads session from SharedPreferences if the singleton
     * does not exist yet OR if it hasn't been loaded.
     */
    public static synchronized ZutnikSession getInstance(Context context) {
        if (instance == null) {
            instance = new ZutnikSession();
        }
        // Always update context to ensure we have a valid reference
        if (context != null) {
            instance.appContext = context.getApplicationContext();
        }
        if (!instance.loaded && instance.appContext != null) {
            instance.loadFromPreferences(instance.appContext);
            instance.loaded = true;
        }
        if (instance.appContext != null) {
            ImageCache.init(instance.appContext); // Ensure cache is ready
        }
        return instance;
    }

    /**
     * Can be called e.g. in onCreate() of HomeActivity / PlanActivity / widget
     * service
     * when we only want to ensure that data from SharedPreferences is loaded.
     */
    public static synchronized void initializeFromPreferences(Context context) {
        if (instance == null) {
            instance = new ZutnikSession();
        }
        if (context != null) {
            instance.appContext = context.getApplicationContext();
        }
        if (!instance.loaded && instance.appContext != null) {
            instance.loadFromPreferences(instance.appContext);
            instance.loaded = true;
        }
        if (instance.appContext != null) {
            ImageCache.init(instance.appContext);
        }
    }

    /**
     * Clears in-memory session and stored data in SharedPreferences.
     * Useful on logout.
     */
    public static synchronized void clear(Context context) {
        clearSessionData(context);
    }

    /**
     * Clears only session-related keys while keeping unrelated preference values
     * (e.g. last login hint).
     */
    public static synchronized void clearSessionData(Context context) {
        instance = null;
        SharedPreferences prefs = getPreferences(context);
        prefs.edit()
                .remove(KEY_USER_ID)
                .remove(KEY_AUTH_KEY)
                .remove(KEY_USERNAME)
                .remove(KEY_IMAGE_URL)
                .remove(KEY_ACTIVE_STUDY_INDEX)
                .remove(KEY_ACTIVE_STUDY_ID)
                .remove(KEY_STUDIES_JSON)
                .remove(KEY_LOGIN_TYPE)
                .remove(KEY_USOS_ACCESS_TOKEN)
                .remove(KEY_USOS_ACCESS_TOKEN_SEC)
                .remove(KEY_USOS_STUDENT_NUMBER)
                .apply();
    }

    public static synchronized Context getAppContextOrNull() {
        if (instance == null) {
            return null;
        }
        return instance.appContext;
    }

    // endregion

    // region Login-type constants

    /** Legacy ZUT proxy authentication. */
    public static final String LOGIN_TYPE_ZUT = "zutnik";
    /** USOS API OAuth 1.0a authentication. */
    public static final String LOGIN_TYPE_USOS = "usos";
    /** Local preview mode without external API credentials. */
    public static final String LOGIN_TYPE_DEMO = "demo";

    // endregion

    // region SharedPreferences

    private static final String PREFS_NAME = "zutnik_prefs";

    private static final String KEY_USER_ID               = "user_id";
    private static final String KEY_AUTH_KEY              = "auth_key";
    private static final String KEY_USERNAME              = "username";
    private static final String KEY_IMAGE_URL             = "image_url";
    private static final String KEY_ACTIVE_STUDY_INDEX    = "active_study_idx";
    private static final String KEY_ACTIVE_STUDY_ID       = "active_study_id";
    private static final String KEY_STUDIES_JSON          = "studies_json";
    private static final String KEY_LOGIN_TYPE            = "login_type";
    private static final String KEY_USOS_ACCESS_TOKEN     = "usos_access_token";
    private static final String KEY_USOS_ACCESS_TOKEN_SEC = "usos_access_token_secret";
    private static final String KEY_USOS_STUDENT_NUMBER   = "usos_student_number";

    private Context appContext;

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

    // USOS OAuth tokens (populated only when loginType == LOGIN_TYPE_USOS)
    private String loginType = LOGIN_TYPE_ZUT;
    private String usosAccessToken;
    private String usosAccessTokenSecret;
    private String studentNumber;

    // Studies data (similar to $_SESSION['STUDIES'], ACTIVE_STUDY_IDX)
    // Persisted in SharedPreferences to keep the same active study across app restarts.
    private List<Study> studies;
    private int activeStudyIndex = 0;
    private String activeStudyId;

    // endregion

    // region Construction

    private ZutnikSession() {
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

        this.userId               = prefs.getString(KEY_USER_ID, null);
        this.authKey              = prefs.getString(KEY_AUTH_KEY, null);
        this.username             = prefs.getString(KEY_USERNAME, null);
        this.imageUrl             = prefs.getString(KEY_IMAGE_URL, null);
        this.activeStudyIndex     = prefs.getInt(KEY_ACTIVE_STUDY_INDEX, 0);
        this.activeStudyId        = prefs.getString(KEY_ACTIVE_STUDY_ID, null);
        this.loginType            = prefs.getString(KEY_LOGIN_TYPE, LOGIN_TYPE_ZUT);
        this.usosAccessToken      = prefs.getString(KEY_USOS_ACCESS_TOKEN, null);
        this.usosAccessTokenSecret = prefs.getString(KEY_USOS_ACCESS_TOKEN_SEC, null);
        this.studentNumber        = prefs.getString(KEY_USOS_STUDENT_NUMBER, null);

        String studiesJson = prefs.getString(KEY_STUDIES_JSON, null);
        if (studiesJson != null) {
            try {
                JSONArray arr = new JSONArray(studiesJson);
                List<Study> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Study s = new Study();
                    s.przynaleznoscId = normalizeStudyId(o.optString("id", null));
                    s.label = o.optString("lbl", null);
                    list.add(s);
                }
                setStudies(list);
                android.util.Log.d("ZutnikSession", "Loaded " + list.size() + " studies from prefs.");
            } catch (JSONException e) {
                android.util.Log.e("ZutnikSession", "Error loading studies", e);
                this.studies = null;
                this.activeStudyIndex = 0;
                this.activeStudyId = null;
            }
        } else {
            this.studies = null;
            this.activeStudyIndex = 0;
            this.activeStudyId = null;
        }
    }

    /**
     * Saves the current session state to SharedPreferences using stored appContext.
     * Safe to call from Repositories if session was initialized with context.
     */
    public void saveToPreferences() {
        if (appContext != null) {
            saveToPreferences(appContext);
        } else {
            android.util.Log.e("ZutnikSession", "Cannot save session: appContext is null!");
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
        e.putString(KEY_LOGIN_TYPE, loginType != null ? loginType : LOGIN_TYPE_ZUT);
        if (usosAccessToken != null) {
            e.putString(KEY_USOS_ACCESS_TOKEN, usosAccessToken);
        } else {
            e.remove(KEY_USOS_ACCESS_TOKEN);
        }
        if (usosAccessTokenSecret != null) {
            e.putString(KEY_USOS_ACCESS_TOKEN_SEC, usosAccessTokenSecret);
        } else {
            e.remove(KEY_USOS_ACCESS_TOKEN_SEC);
        }
        if (studentNumber != null) {
            e.putString(KEY_USOS_STUDENT_NUMBER, studentNumber);
        } else {
            e.remove(KEY_USOS_STUDENT_NUMBER);
        }
        reconcileActiveStudySelection();
        e.putInt(KEY_ACTIVE_STUDY_INDEX, activeStudyIndex);
        if (activeStudyId != null && !activeStudyId.trim().isEmpty()) {
            e.putString(KEY_ACTIVE_STUDY_ID, activeStudyId.trim());
        } else {
            e.remove(KEY_ACTIVE_STUDY_ID);
        }

        if (studies != null && !studies.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Study s : studies) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", normalizeStudyId(s.przynaleznoscId));
                    o.put("lbl", s.label);
                    arr.put(o);
                } catch (JSONException ignored) {
                }
            }
            e.putString(KEY_STUDIES_JSON, arr.toString());
        } else {
            e.remove(KEY_STUDIES_JSON);
        }

        e.apply();
    }

    /**
     * Sets the ZUTnik (legacy proxy) user session.
     * Clears any previously stored USOS tokens.
     */
    public void updateUser(String userId, String username, String authKey, String imageUrl) {
        this.userId = userId;
        this.username = username;
        this.authKey = authKey;
        this.imageUrl = imageUrl;
        this.loginType = LOGIN_TYPE_ZUT;
        this.usosAccessToken = null;
        this.usosAccessTokenSecret = null;
        this.studies = null;
        this.activeStudyIndex = 0;
        this.activeStudyId = null;
        this.loaded = true;
    }

    /**
     * Sets the USOS OAuth user session.
     * Clears the legacy ZUTnik authKey.
     */
    public void updateUsosUser(
            String userId, String username,
            String accessToken, String accessTokenSecret,
            String imageUrl, String studentNumber) {
        this.userId = userId;
        this.username = username;
        this.authKey = null;
        this.imageUrl = imageUrl;
        this.loginType = LOGIN_TYPE_USOS;
        this.usosAccessToken = accessToken;
        this.usosAccessTokenSecret = accessTokenSecret;
        this.studentNumber = studentNumber;
        this.studies = null;
        this.activeStudyIndex = 0;
        this.activeStudyId = null;
        this.loaded = true;
    }

    /**
     * Sets a local demo session. Demo mode intentionally does not carry USOS
     * tokens or the removed legacy API token.
     */
    public void updateDemoUser(String userId, String username, String imageUrl) {
        this.userId = userId;
        this.username = username;
        this.authKey = null;
        this.imageUrl = imageUrl;
        this.loginType = LOGIN_TYPE_DEMO;
        this.usosAccessToken = null;
        this.usosAccessTokenSecret = null;
        this.studentNumber = null;
        this.studies = null;
        this.activeStudyIndex = 0;
        this.activeStudyId = null;
        this.loaded = true;
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

    public String getLoginType() {
        return loginType != null ? loginType : LOGIN_TYPE_ZUT;
    }

    public String getUsosAccessToken() {
        return usosAccessToken;
    }

    public String getUsosAccessTokenSecret() {
        return usosAccessTokenSecret;
    }

    public String getStudentNumber() {
        return studentNumber;
    }

    /** Returns true when the user authenticated via USOS OAuth. */
    public boolean isUsosLogin() {
        return LOGIN_TYPE_USOS.equals(loginType);
    }

    /** Returns true when the app runs in local preview/demo mode. */
    public boolean isDemoLogin() {
        return LOGIN_TYPE_DEMO.equals(loginType);
    }

    /**
     * Returns true if the session contains enough data to use the app
     * (regardless of login method).
     */
    public boolean isLoggedIn() {
        if (userId == null || userId.isEmpty()) return false;
        if (isDemoLogin()) {
            return true;
        }
        if (!isUsosLogin()) {
            return false;
        }
        return usosAccessToken != null
                && !usosAccessToken.isEmpty()
                && usosAccessTokenSecret != null
                && !usosAccessTokenSecret.isEmpty();
    }

    public List<Study> getStudies() {
        return studies;
    }

    public void setStudies(List<Study> studies) {
        if (studies == null) {
            this.studies = null;
            this.activeStudyIndex = 0;
            this.activeStudyId = null;
            return;
        }
        List<Study> copy = new ArrayList<>(studies.size());
        for (Study src : studies) {
            if (src == null) {
                continue;
            }
            Study s = new Study();
            s.przynaleznoscId = normalizeStudyId(src.przynaleznoscId);
            s.label = src.label;
            copy.add(s);
        }
        this.studies = copy;
        reconcileActiveStudySelection();
    }

    public int getActiveStudyIndex() {
        return activeStudyIndex;
    }

    public void setActiveStudyIndex(int activeStudyIndex) {
        this.activeStudyIndex = activeStudyIndex;
        if (studies == null || studies.isEmpty()) {
            this.activeStudyIndex = 0;
            this.activeStudyId = null;
            return;
        }
        if (this.activeStudyIndex < 0 || this.activeStudyIndex >= studies.size()) {
            this.activeStudyIndex = 0;
        }
        Study active = studies.get(this.activeStudyIndex);
        this.activeStudyId = active != null ? normalizeStudyId(active.przynaleznoscId) : null;
    }

    public String getActiveStudyId() {
        return activeStudyId;
    }

    public void setActiveStudyId(String activeStudyId) {
        this.activeStudyId = normalizeStudyId(activeStudyId);
        reconcileActiveStudySelection();
    }

    public Study getActiveStudy() {
        if (studies == null || studies.isEmpty()) {
            return null;
        }
        int idx = activeStudyIndex;
        if (idx < 0 || idx >= studies.size()) {
            idx = 0;
        }
        return studies.get(idx);
    }

    private void reconcileActiveStudySelection() {
        if (studies == null || studies.isEmpty()) {
            activeStudyIndex = 0;
            activeStudyId = null;
            return;
        }

        String wantedId = normalizeStudyId(activeStudyId);
        if (wantedId != null && !wantedId.isEmpty()) {
            for (int i = 0; i < studies.size(); i++) {
                Study s = studies.get(i);
                String candidateId = s != null ? normalizeStudyId(s.przynaleznoscId) : null;
                if (candidateId != null && wantedId.equals(candidateId)) {
                    activeStudyIndex = i;
                    activeStudyId = wantedId;
                    return;
                }
            }
        }

        if (activeStudyIndex < 0 || activeStudyIndex >= studies.size()) {
            activeStudyIndex = 0;
        }

        Study active = studies.get(activeStudyIndex);
        activeStudyId = active != null ? normalizeStudyId(active.przynaleznoscId) : null;
    }

    private String normalizeStudyId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String normalized = rawId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    // endregion
}
