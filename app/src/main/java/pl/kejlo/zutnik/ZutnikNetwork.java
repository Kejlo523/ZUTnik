package pl.kejlo.zutnik;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;

public final class ZutnikNetwork {

    private static OkHttpClient sClient;

    private ZutnikNetwork() {
    }

    public static String getBrowserUserAgent() {
        return BrowserUserAgent.current();
    }

    public static synchronized OkHttpClient getClient() {
        if (sClient == null) {
            sClient = createClient();
        }
        return sClient;
    }

    private static OkHttpClient createClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .connectionSpecs(Collections.singletonList(ConnectionSpec.MODERN_TLS))
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .header("User-Agent", getBrowserUserAgent())
                                .build()))
                .build();
    }
}
