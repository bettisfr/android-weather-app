package it.skynow.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

public final class SkyTheme {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private SkyTheme() { }

    static int preference(Context context) {
        int selection = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("theme", SYSTEM);
        return selection == LIGHT || selection == DARK ? selection : SYSTEM;
    }

    static void savePreference(Context context, int selection) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("theme", selection).apply();
    }

    public static Context withPreference(Context context) {
        context = SkyLanguage.withPreference(context);
        int selection = preference(context);
        if (selection == SYSTEM) return context;
        Configuration current = context.getResources().getConfiguration();
        Configuration override = new Configuration();
        // A configuration context replaces, not extends, the override it is created from,
        // so carry over the app language chosen above.
        override.setLocales(current.getLocales());
        override.uiMode = (current.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (selection == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(override);
    }

    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void applySystemBars(Activity activity) {
        applySystemBars(activity.getWindow(), activity, activity.getColor(R.color.sky_background));
    }

    public static void applySystemBars(Window window, Context context, int background) {
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        boolean light = !isDark(context);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            applyLegacyBarAppearance(window, light);
        }
    }

    @SuppressWarnings("deprecation") // Required on Android 8–10.
    private static void applyLegacyBarAppearance(Window window, boolean light) {
        View decor = window.getDecorView();
        int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask) | (light ? mask : 0));
    }
}
