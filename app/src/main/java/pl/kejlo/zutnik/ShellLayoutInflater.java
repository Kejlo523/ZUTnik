package pl.kejlo.zutnik;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class ShellLayoutInflater {

    private ShellLayoutInflater() {
    }

    static View inflateTabContent(
            @NonNull LayoutInflater inflater,
            @LayoutRes int layoutId,
            @Nullable ViewGroup container) {
        View root = inflater.inflate(layoutId, container, false);
        stripEmbeddedBottomNav(root);
        expandMainContent(root);
        return unwrapContentRoot(root);
    }

    /**
     * Returns the inner content panel when a layout still wraps it in a legacy
     * shell root ({@code mainShellRoot} + bottom nav). Avoids duplicate shell ids
     * when embedding inside {@link R.layout#activity_main_shell}.
     */
    static View unwrapContentRoot(View inflated) {
        if (inflated == null) {
            return null;
        }
        View content = inflated.findViewById(R.id.drawerContentRoot);
        if (content == null) {
            content = inflated.findViewById(R.id.planCoordinatorRoot);
        }
        if (content == null || content == inflated) {
            return inflated;
        }
        ViewGroup parent = (ViewGroup) content.getParent();
        if (parent != null) {
            parent.removeView(content);
        }
        return content;
    }

    private static void stripEmbeddedBottomNav(View root) {
        View bottomNav = root.findViewById(R.id.bottomNavigation);
        if (bottomNav == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) bottomNav.getParent();
        if (parent != null) {
            parent.removeView(bottomNav);
        }
    }

    private static void expandMainContent(View root) {
        View content = root.findViewById(R.id.drawerContentRoot);
        if (content == null) {
            content = root.findViewById(R.id.planCoordinatorRoot);
        }
        if (content == null) {
            return;
        }
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.weight = 0f;
            content.setLayoutParams(params);
        } else if (lp != null) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            content.setLayoutParams(lp);
        }
    }
}
