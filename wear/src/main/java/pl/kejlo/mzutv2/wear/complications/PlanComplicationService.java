package pl.kejlo.mzutv2.wear.complications;

import android.content.Context;
import android.graphics.drawable.Icon;

import androidx.annotation.NonNull;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;

import java.time.ZonedDateTime;

import pl.kejlo.mzutv2.wear.R;
import pl.kejlo.mzutv2.wear.model.WearPlanSnapshot;
import pl.kejlo.mzutv2.wear.sync.WearSnapshotStore;
import pl.kejlo.mzutv2.wear.util.WearLocaleManager;
import pl.kejlo.mzutv2.wear.util.WearScheduleUtils;

public class PlanComplicationService extends ComplicationDataSourceService {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(WearLocaleManager.wrap(newBase));
    }

    @Override
    public void onComplicationRequest(@NonNull ComplicationRequest request,
            @NonNull ComplicationDataSourceService.ComplicationRequestListener listener) {
        WearPlanSnapshot snap = WearSnapshotStore.load(this);
        ComplicationData data = buildData(request, snap);
        try {
            listener.onComplicationData(data);
        } catch (android.os.RemoteException ignored) {
        }
    }

    private ComplicationData buildData(ComplicationRequest request, WearPlanSnapshot snap) {
        if (snap == null || snap.loginRequired) {
            return new NoDataComplicationData();
        }

        MonochromaticImage icon = buildIcon();

        WearScheduleUtils.NextEventInfo next = WearScheduleUtils.findNextEvent(
                snap, ZonedDateTime.now());
        if (next == null || next.event == null) {
            PlainComplicationText text = new PlainComplicationText.Builder(
                    getString(R.string.complication_no_data)).build();
            return new ShortTextComplicationData.Builder(text, text)
                    .setMonochromaticImage(icon)
                    .build();
        }

        WearPlanSnapshot.Event ev = next.event;
        String title = (ev.title != null && !ev.title.isEmpty()) ? ev.title : "";
        String time = (ev.time != null && !ev.time.isEmpty()) ? ev.time : "";
        String room = (ev.room != null && !ev.room.isEmpty()) ? ev.room : "";
        String eta = WearScheduleUtils.formatEta(this, ZonedDateTime.now(), next.start);
        String etaShort = WearScheduleUtils.formatEtaShort(this, ZonedDateTime.now(), next.start);

        if (request.getComplicationType() == ComplicationType.LONG_TEXT) {
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) {
                sb.append(title);
            }
            if (!eta.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n" : "").append(eta);
            }
            if (!time.isEmpty()) {
                sb.append(" \u00B7 ").append(time);
            }
            if (!room.isEmpty()) {
                sb.append("\n").append(room);
            }

            ComplicationText longText = new PlainComplicationText.Builder(sb.toString()).build();
            ComplicationText contentDesc = new PlainComplicationText.Builder(title).build();
            return new LongTextComplicationData.Builder(longText, contentDesc)
                    .setMonochromaticImage(icon)
                    .build();
        }

        String shortText = !etaShort.isEmpty() ? etaShort : (!time.isEmpty() ? time : title);
        ComplicationText shortCompText = new PlainComplicationText.Builder(shortText).build();
        ComplicationText titleText = new PlainComplicationText.Builder(title).build();
        return new ShortTextComplicationData.Builder(shortCompText, titleText)
                .setMonochromaticImage(icon)
                .build();
    }

    @Override
    public ComplicationData getPreviewData(@NonNull ComplicationType type) {
        MonochromaticImage icon = buildIcon();
        PlainComplicationText text = new PlainComplicationText.Builder(
                getString(R.string.complication_preview_eta_short)).build();
        PlainComplicationText title = new PlainComplicationText.Builder(
                getString(R.string.complication_preview_title)).build();
        if (type == ComplicationType.LONG_TEXT) {
            return new LongTextComplicationData.Builder(
                    new PlainComplicationText.Builder(
                            getString(R.string.complication_preview_long)).build(),
                    title)
                    .setMonochromaticImage(icon)
                    .build();
        }
        return new ShortTextComplicationData.Builder(text, title)
                .setMonochromaticImage(icon)
                .build();
    }

    private MonochromaticImage buildIcon() {
        return new MonochromaticImage.Builder(
                Icon.createWithResource(this, R.drawable.ic_complication))
                .build();
    }
}
