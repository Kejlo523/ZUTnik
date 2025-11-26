package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Centralna sesja użytkownika.
 *
 *      Trzyma w RAM dane sesji (jak do tej pory)
 *      Potrafi wczytać / zapisać się do SharedPreferences
 *      Dzięki temu po ubiciu procesu dalej mamy:
 *      - userId
 *      - authKey
 *      - username
 *      - imageUrl
 *      - activeStudyIndex
 *
 * Uwaga:
 *  - docelowo w Activity / Service / Widget będziemy wołać getInstance(context)
 *  - stara wersja getInstance() zostaje, ale starajmy się odchodzić od niej
 */
public class MzutSession {

    // ---------- Singleton ----------

    private static MzutSession instance;

    /**
     * Stara wersja – NIE ładuje z SharedPreferences.
     * Bezpieczna tylko wtedy, gdy wcześniej ktoś wywołał
     * getInstance(context) / initializeFromPreferences(context).
     */
    public static synchronized MzutSession getInstance() {
        if (instance == null) {
            instance = new MzutSession();
        }
        return instance;
    }

    /**
     * Nowa, preferowana metoda – od razu próbuje wczytać sesję z SharedPreferences,
     * jeśli singleton jeszcze nie istnieje.
     */
    public static synchronized MzutSession getInstance(Context context) {
        if (instance == null) {
            instance = new MzutSession();
            instance.loadFromPreferences(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Można wywołać np. w onCreate() w HomeActivity / PlanActivity / serwisie widgetu,
     * jeśli chcemy tylko mieć pewność, że dane z SharedPreferences są wczytane.
     */
    public static synchronized void initializeFromPreferences(Context context) {
        if (instance == null) {
            instance = new MzutSession();
        }
        instance.loadFromPreferences(context.getApplicationContext());
    }

    /**
     * Czyści sesję w RAM + czyści zapisane dane w SharedPreferences.
     * Przyda się przy wylogowaniu.
     */
    public static synchronized void clear(Context context) {
        // wyczyść singleton
        instance = null;

        // wyczyść SharedPreferences
        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    // ---------- Klucze / SharedPreferences ----------

    private static final String PREFS_NAME              = "mzut_prefs";
    private static final String KEY_USER_ID             = "user_id";
    private static final String KEY_AUTH_KEY            = "auth_key";
    private static final String KEY_USERNAME            = "username";
    private static final String KEY_IMAGE_URL           = "image_url";
    private static final String KEY_ACTIVE_STUDY_INDEX  = "active_study_idx";

    // ---------- Dane sesji w RAM ----------

    // dane użytkownika
    private String userId;    // USERID
    private String username;  // USERNAME (imię + nazwisko)
    private String authKey;   // AUTHKEY (token)
    private String imageUrl;  // IMAGEURL

    // dane studiów (jak $_SESSION['STUDIES'], ACTIVE_STUDY_IDX)
    private List<Study> studies;
    private int activeStudyIndex = 0;

    // ---------- Konstruktor prywatny ----------

    private MzutSession() {
        // nic specjalnego – realna inicjalizacja przez loadFromPreferences()
    }

    // ---------- Publiczne API – wczytywanie / zapisywanie ----------

    /**
     * Wczytuje dane sesji z SharedPreferences do pól obiektu.
     * Nie tworzy nowego singletona – używa obecnego (this).
     */
    private void loadFromPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        this.userId           = prefs.getString(KEY_USER_ID, null);
        this.authKey          = prefs.getString(KEY_AUTH_KEY, null);
        this.username         = prefs.getString(KEY_USERNAME, null);
        this.imageUrl         = prefs.getString(KEY_IMAGE_URL, null);
        this.activeStudyIndex = prefs.getInt(KEY_ACTIVE_STUDY_INDEX, 0);
    }

    /**
     * Zapisuje aktualny stan sesji do SharedPreferences.
     * Wołamy np. po zalogowaniu / zmianie kierunku / edycji profilu.
     */
    public void saveToPreferences(Context context) {
        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_USER_ID, userId);
        e.putString(KEY_AUTH_KEY, authKey);
        e.putString(KEY_USERNAME, username);
        e.putString(KEY_IMAGE_URL, imageUrl);
        e.putInt(KEY_ACTIVE_STUDY_INDEX, activeStudyIndex);
        e.apply();
    }

    /**
     * Wygodna metoda do ustawienia całego użytkownika naraz.
     * (do użycia np. w LoginActivity po udanym logowaniu)
     */
    public void updateUser(String userId, String username, String authKey, String imageUrl) {
        this.userId = userId;
        this.username = username;
        this.authKey = authKey;
        this.imageUrl = imageUrl;
    }

    // ---------- Gettery / settery ----------

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
}
