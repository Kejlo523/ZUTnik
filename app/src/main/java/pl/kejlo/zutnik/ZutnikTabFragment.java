package pl.kejlo.zutnik;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

abstract class ZutnikTabFragment extends Fragment {

    protected MainShellActivity shellActivity() {
        return (MainShellActivity) requireActivity();
    }

    protected AppCompatActivity hostActivity() {
        return (AppCompatActivity) requireActivity();
    }

    protected boolean isTabCurrentlyVisible() {
        return isAdded() && !isHidden() && getView() != null;
    }

    @Nullable
    protected Toolbar getTabToolbar() {
        return null;
    }

    @Nullable
    protected MainNavHelper.Screen getTabScreen() {
        return null;
    }

    protected void onTabActivated() {
        if (!isTabCurrentlyVisible()) {
            return;
        }
        Toolbar toolbar = getTabToolbar();
        if (toolbar != null) {
            hostActivity().setSupportActionBar(toolbar);
            MainNavHelper.styleToolbarPublic(hostActivity(), toolbar);
            MainNavHelper.Screen screen = getTabScreen();
            if (screen != null) {
                MainNavHelper.applyToolbarTitle(hostActivity(), toolbar, screen);
            }
        }
        invalidateActivityMenu();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!isAdded()) {
            return;
        }
        if (hidden) {
            return;
        } else {
            onTabActivated();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isTabCurrentlyVisible()) {
            onTabActivated();
        }
    }

    protected void invalidateActivityMenu() {
        if (!isAdded()) {
            return;
        }
        requireActivity().invalidateMenu();
    }

    protected void showSkeleton(@Nullable View skeleton, @Nullable View content) {
        if (skeleton != null) {
            skeleton.setVisibility(View.VISIBLE);
        }
        if (content != null) {
            content.setVisibility(View.GONE);
        }
    }

    protected void revealContent(@Nullable View skeleton, @Nullable View content) {
        TabContentAnimator.revealContent(skeleton, content);
    }
}
