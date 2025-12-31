package pl.kejlo.mzutv2;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

public class NetworkStatusHelper extends LiveData<Boolean> {

    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback;

    public NetworkStatusHelper(Context context) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // Check for valid internet capability
                postValue(true);
            }

            @Override
            public void onLost(@NonNull Network network) {
                postValue(false);
            }

            @Override
            public void onUnavailable() {
                postValue(false);
            }
        };
    }

    @Override
    protected void onActive() {
        super.onActive();
        updateInitialState();
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    @Override
    protected void onInactive() {
        super.onInactive();
        connectivityManager.unregisterNetworkCallback(networkCallback);
    }

    private void updateInitialState() {
        if (connectivityManager == null) {
            postValue(false);
            return;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            postValue(false);
            return;
        }
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        boolean isConnected = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        postValue(isConnected);
    }

    // Static helper for non-lifecycle contexts
    public static boolean isNetworkAvailable(Context context) {
        if (context == null)
            return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
            return false;
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null)
            return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
