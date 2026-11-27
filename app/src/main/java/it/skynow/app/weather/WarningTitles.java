package it.skynow.app.weather;

import android.content.Context;
import it.skynow.app.R;
import java.util.Locale;

/** Localized titles for MeteoAlarm warnings, whose feed titles are English, e.g. "Yellow Wind Warning". */
public final class WarningTitles {
    private WarningTitles() {}

    /**
     * The title to show for a feed {@code event}: translated when the app language asks for it and
     * the level and hazard are known, otherwise the feed's own title.
     */
    public static String title(Context context, String event) {
        if (!context.getResources().getBoolean(R.bool.translate_warning_titles)) return event;
        int[] parts = parts(event);
        if (parts == null) return event;
        return context.getString(R.string.warning_title, context.getString(parts[0]), context.getString(parts[1]));
    }

    /** The level and hazard string resources of a feed event, or null when either is unknown. */
    static int[] parts(String event) {
        String[] words = event.toLowerCase(Locale.ROOT).split(" ", 2);
        if (words.length != 2) return null;
        int level = levelRes(words[0]);
        int hazard = hazardRes(words[1]);
        return level == 0 || hazard == 0 ? null : new int[]{level, hazard};
    }

    private static int levelRes(String level) {
        switch (level) {
            case "yellow": return R.string.warning_level_yellow;
            case "orange": return R.string.warning_level_orange;
            case "red": return R.string.warning_level_red;
            default: return 0;
        }
    }

    private static int hazardRes(String hazard) {
        switch (hazard) {
            case "wind warning": return R.string.warning_hazard_wind;
            case "rain warning": return R.string.warning_hazard_rain;
            case "thunderstorm warning": return R.string.warning_hazard_thunderstorm;
            case "snow-ice warning": case "snow/ice warning": return R.string.warning_hazard_snow_ice;
            case "fog warning": return R.string.warning_hazard_fog;
            case "high temperature warning": return R.string.warning_hazard_high_temperature;
            case "low temperature warning": return R.string.warning_hazard_low_temperature;
            case "coastal event warning": return R.string.warning_hazard_coastal_event;
            case "flooding warning": return R.string.warning_hazard_flooding;
            default: return 0;
        }
    }
}
