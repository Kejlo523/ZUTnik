package pl.kejlo.mzutv2;

import java.util.List;

public class MzutSession {

    private static MzutSession instance;

    public static synchronized MzutSession getInstance() {
        if (instance == null) {
            instance = new MzutSession();
        }
        return instance;
    }

    // dane użytkownika
    private String userId;    // USERID
    private String username;  // USERNAME
    private String authKey;   // AUTHKEY
    private String imageUrl;  // IMAGEURL

    // dane studiów (jak $_SESSION['STUDIES'], ACTIVE_STUDY_IDX)
    private List<Study> studies;
    private int activeStudyIndex = 0;

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
