package pl.kejlo.zutnik;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.PopupMenu;

public final class ZutnikPopupMenu {

    private ZutnikPopupMenu() {
    }

    public static PopupMenu create(Context context, View anchor) {
        return new PopupMenu(
                new ContextThemeWrapper(context, R.style.ThemeOverlay_ZUTnik_PopupMenu),
                anchor);
    }
}
