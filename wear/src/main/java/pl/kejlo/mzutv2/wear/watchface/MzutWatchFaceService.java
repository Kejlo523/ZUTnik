package pl.kejlo.mzutv2.wear.watchface;

import android.content.Context;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.wear.watchface.ComplicationSlotsManager;
import androidx.wear.watchface.Renderer;
import androidx.wear.watchface.WatchFace;
import androidx.wear.watchface.WatchFaceService;
import androidx.wear.watchface.WatchFaceType;
import androidx.wear.watchface.WatchState;
import androidx.wear.watchface.style.CurrentUserStyleRepository;
import androidx.wear.watchface.style.UserStyleSchema;

import java.util.Collections;

import kotlin.coroutines.Continuation;
import pl.kejlo.mzutv2.wear.util.WearLocaleManager;

public class MzutWatchFaceService extends WatchFaceService {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(WearLocaleManager.wrap(newBase));
    }

    @NonNull
    @Override
    public UserStyleSchema createUserStyleSchema() {
        return new UserStyleSchema(Collections.emptyList());
    }

    @NonNull
    @Override
    public ComplicationSlotsManager createComplicationSlotsManager(
            @NonNull CurrentUserStyleRepository currentUserStyleRepository) {
        return new ComplicationSlotsManager(Collections.emptyList(), currentUserStyleRepository);
    }

    @Override
    public Object createWatchFace(@NonNull SurfaceHolder surfaceHolder,
            @NonNull WatchState watchState,
            @NonNull ComplicationSlotsManager complicationSlotsManager,
            @NonNull CurrentUserStyleRepository currentUserStyleRepository,
            @NonNull Continuation<? super WatchFace> continuation) {
        Renderer renderer = new MzutWatchFaceRenderer(
                this,
                surfaceHolder,
                watchState,
                currentUserStyleRepository);
        return new WatchFace(WatchFaceType.DIGITAL, renderer);
    }
}
