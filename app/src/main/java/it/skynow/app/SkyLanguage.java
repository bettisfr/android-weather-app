package it.skynow.app;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

public final class SkyLanguage {
    private static final Locale[] CHOICES = {null, Locale.ITALY, Locale.US};

    private SkyLanguage() {}

    static int preference(Context context) {
        int selection = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("language", 0);
        return selection >= 0 && selection < CHOICES.length ? selection : 0;
    }

    static void savePreference(Context context, int selection) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("language", selection).apply();
    }

    public static Context withPreference(Context context) {
        Locale locale = CHOICES[preference(context)];
        if (locale == null) return context;
        Configuration override = new Configuration();
        override.setLocale(locale);
        return context.createConfigurationContext(override);
    }
}
