package pl.kejlo.zutnik;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.navigation.NavigationBarView;

/**
 * Mounts {@link R.layout#activity_main_shell} (bottom nav or tablet rail) and
 * inflates screen content without duplicate navigation bars.
 */
final class ShellHostHelper {

    static final class MountedContent {
        final View contentRoot;
        final View mainShellRoot;
        final NavigationBarView shellNavigation;

        MountedContent(View contentRoot, View mainShellRoot, NavigationBarView shellNavigation) {
            this.contentRoot = contentRoot;
            this.mainShellRoot = mainShellRoot;
            this.shellNavigation = shellNavigation;
        }
    }

    private ShellHostHelper() {
    }

    static MountedContent mountContentLayout(
            AppCompatActivity activity,
            @LayoutRes int contentLayoutId,
            MainNavHelper.Screen screen) {
        activity.setContentView(R.layout.activity_main_shell);

        View mainShellRoot = activity.findViewById(R.id.mainShellRoot);
        ViewGroup fragmentContainer = activity.findViewById(R.id.fragmentContainer);
        View contentRoot = ShellLayoutInflater.inflateTabContent(
                activity.getLayoutInflater(),
                contentLayoutId,
                fragmentContainer);
        fragmentContainer.addView(
                contentRoot,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        Toolbar toolbar = contentRoot.findViewById(R.id.toolbar);
        NavigationBarView shellNavigation = setupNavigation(activity, mainShellRoot, toolbar, screen);
        return new MountedContent(contentRoot, mainShellRoot, shellNavigation);
    }

    static NavigationBarView setupNavigation(
            AppCompatActivity activity,
            View mainShellRoot,
            @Nullable Toolbar toolbar,
            MainNavHelper.Screen screen) {
        NavigationBarView shellNavigation = MainNavHelper.findShellNavigation(activity);
        MainNavHelper.setup(activity, mainShellRoot, shellNavigation, toolbar, screen);
        return shellNavigation;
    }

    static NavigationBarView setupNavigation(
            AppCompatActivity activity,
            MainNavHelper.Screen screen) {
        return setupNavigation(
                activity,
                activity.findViewById(R.id.mainShellRoot),
                null,
                screen);
    }
}
