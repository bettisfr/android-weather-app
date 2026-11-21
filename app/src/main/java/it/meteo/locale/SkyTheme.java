package it.meteo.locale;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

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
        int selection = preference(context);
        if (selection == SYSTEM) return context;
        Configuration override = new Configuration();
        override.uiMode = (context.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (selection == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(override);
    }

    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void applySystemBars(Activity activity) {
        int background = activity.getColor(R.color.sky_background);
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        activity.getWindow().getDecorView().setSystemUiVisibility(isDark(activity) ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
