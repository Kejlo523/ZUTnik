package pl.kejlo.mzutv2;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "mZUTv2";

    // Zostawiamy tylko to, co lokalne dla ekranu logowania
    private static final String PREFS_NAME     = "mzut_prefs";
    private static final String KEY_LAST_LOGIN = "last_login";

    private EditText editLogin;
    private EditText editPass;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1️⃣ Spróbuj wczytać sesję z SharedPreferences (nowy MzutSession)
        MzutSession.initializeFromPreferences(this);
        MzutSession session = MzutSession.getInstance();

        // Jeśli mamy zapisane userId + authKey → od razu idziemy do HomeActivity
        if (session.getAuthKey() != null && session.getUserId() != null) {
            Intent i = new Intent(LoginActivity.this, HomeActivity.class);
            startActivity(i);
            finish();
            return;
        }

        // 2️⃣ Jeśli nie ma sesji → standardowy ekran logowania
        setContentView(R.layout.activity_login);

        editLogin = findViewById(R.id.editLogin);
        editPass  = findViewById(R.id.editPass);
        btnLogin  = findViewById(R.id.btnLogin);

        // Wstaw ostatni login (podpowiedź)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastLogin = prefs.getString(KEY_LAST_LOGIN, "");
        if (!lastLogin.isEmpty()) {
            editLogin.setText(lastLogin);
        }

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String login = editLogin.getText().toString().trim();
        String pass  = editPass.getText().toString().trim();

        if (login.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Wpisz login i hasło!", Toast.LENGTH_SHORT).show();
            return;
        }

        // zapamiętaj login (do podpowiedzi)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_LOGIN, login).apply();

        String token    = MzutTokenGenerator.generateToken(login, pass);
        String tokenJpg = MzutTokenGenerator.generateToken(login, null);

        Log.d(TAG, "Generated token: " + token);

        new AuthTask(login, pass, token, tokenJpg).execute();
    }

    private class AuthTask extends AsyncTask<Void, Void, JSONObject> {

        String login;
        String password;
        String token;
        String tokenJpg;
        Exception error;

        AuthTask(String login, String password, String token, String tokenJpg) {
            this.login = login;
            this.password = password;
            this.token = token;
            this.tokenJpg = tokenJpg;
        }

        @Override
        protected JSONObject doInBackground(Void... voids) {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("login", login);
                params.put("password", password);
                params.put("token", token);
                params.put("tokenJpg", tokenJpg);

                return MzutApi.callApi("getAuthorization", params);
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(JSONObject auth) {
            if (error != null) {
                Toast.makeText(LoginActivity.this, "Błąd: " + error.getMessage(), Toast.LENGTH_LONG).show();
                Log.e(TAG, "API error", error);
                return;
            }

            if (auth == null) {
                Toast.makeText(LoginActivity.this, "Brak odpowiedzi z serwera", Toast.LENGTH_LONG).show();
                return;
            }

            Log.d(TAG, "AUTH RESULT: " + auth.toString());

            String status = auth.optString("logInStatus",
                    auth.optString("loginInStatus", ""));

            if (!"OK".equalsIgnoreCase(status)) {
                if ("SYSTEM ERROR".equalsIgnoreCase(status)) {
                    Toast.makeText(LoginActivity.this,
                            "Błąd systemu ZUT (SYSTEM ERROR).",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(LoginActivity.this,
                            "Nieprawidłowy login lub hasło.",
                            Toast.LENGTH_LONG).show();
                }
                return;
            }

            String userId   = auth.optString("login", login);
            String first    = auth.optString("pierwszeImie", "");
            String last     = auth.optString("nazwisko", "");
            String username = (first + " " + last).trim();
            String authKey  = auth.optString("token", token);
            String imageUrl = "https://www.zut.edu.pl/app-json-proxy/image/?userId="
                    + userId + "&tokenJpg="
                    + auth.optString("tokenJpg", tokenJpg);

            // 3️⃣ Zapis do sesji (RAM) + zapis trwały przez MzutSession
            MzutSession session = MzutSession.getInstance(LoginActivity.this);
            session.updateUser(userId, username, authKey, imageUrl);
            session.saveToPreferences(LoginActivity.this);

            Toast.makeText(LoginActivity.this,
                    "Zalogowano jako " + username,
                    Toast.LENGTH_LONG).show();

            Intent i = new Intent(LoginActivity.this, HomeActivity.class);
            startActivity(i);
            finish();
        }
    }
}
