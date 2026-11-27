package it.skynow.app.weather;

import android.content.Context;
import it.skynow.app.R;

/** Icons and localized descriptions for WMO weather interpretation codes, as used by Open-Meteo. */
public final class WeatherCodes {
    // Private-use glyphs supplied by the app's weather icon font.
    private static final char LIGHT_RAIN = '';
    private static final char MODERATE_RAIN = '';
    private static final char HEAVY_RAIN = '';
    private static final char FOG = '';
    private static final char SNOW = '';
    private static final char RIME_FOG = '';
    private static final char LIGHT_DRIZZLE = '';
    private static final char MODERATE_DRIZZLE = '';
    private static final char HEAVY_DRIZZLE = '';
    private static final char CLOUDY_NIGHT = '';
    private static final char CLOUDY_DAY = '';
    private static final char MOSTLY_CLEAR_NIGHT = '';
    private static final char MOSTLY_CLEAR_DAY = '';
    private static final char LIGHT_SNOW = '';
    private static final char MODERATE_SNOW = '';
    private static final char SNOW_GRAINS = '';
    private static final char LIGHT_FREEZING_DRIZZLE = '';
    private static final char HEAVY_FREEZING_DRIZZLE = '';
    private static final char LIGHT_SHOWERS = '';
    private static final char MODERATE_SHOWERS = '';
    private static final char HEAVY_SHOWERS = '';
    private static final char LIGHT_FREEZING_RAIN = '';
    private static final char HEAVY_FREEZING_RAIN = '';
    private static final char LIGHT_SNOW_SHOWERS = '';
    private static final char HEAVY_SNOW_SHOWERS = '';
    private static final char THUNDERSTORM = '';
    private static final char THUNDERSTORM_LIGHT_HAIL = '';
    private static final char THUNDERSTORM_HEAVY_HAIL = '';

    private WeatherCodes() {}

    public static String symbol(int code, boolean night) {
        if (code == 0) return night ? "🌙" : "☀️";
        if (code == 1) return String.valueOf(night ? MOSTLY_CLEAR_NIGHT : MOSTLY_CLEAR_DAY);
        if (code == 2) return String.valueOf(night ? CLOUDY_NIGHT : CLOUDY_DAY);
        if (code == 3) return "☁️";
        if (code == 48) return String.valueOf(RIME_FOG);
        if (code < 48) return String.valueOf(FOG);
        if (code == 51) return String.valueOf(LIGHT_DRIZZLE);
        if (code == 53) return String.valueOf(MODERATE_DRIZZLE);
        if (code == 55) return String.valueOf(HEAVY_DRIZZLE);
        if (code == 56) return String.valueOf(LIGHT_FREEZING_DRIZZLE);
        if (code == 57) return String.valueOf(HEAVY_FREEZING_DRIZZLE);
        if (code == 71) return String.valueOf(LIGHT_SNOW);
        if (code == 73) return String.valueOf(MODERATE_SNOW);
        if (code == 77) return String.valueOf(SNOW_GRAINS);
        if (code == 75) return String.valueOf(SNOW);
        if (code == 85) return String.valueOf(LIGHT_SNOW_SHOWERS);
        if (code == 86) return String.valueOf(HEAVY_SNOW_SHOWERS);
        if (code == 96) return String.valueOf(THUNDERSTORM_LIGHT_HAIL);
        if (code == 99) return String.valueOf(THUNDERSTORM_HEAVY_HAIL);
        if (code >= 95) return String.valueOf(THUNDERSTORM);
        if (code == 80) return String.valueOf(LIGHT_SHOWERS);
        if (code == 81) return String.valueOf(MODERATE_SHOWERS);
        if (code == 82) return String.valueOf(HEAVY_SHOWERS);
        if (code == 66) return String.valueOf(LIGHT_FREEZING_RAIN);
        if (code == 67) return String.valueOf(HEAVY_FREEZING_RAIN);
        if (code == 65) return String.valueOf(HEAVY_RAIN);
        if (code == 63) return String.valueOf(MODERATE_RAIN);
        return String.valueOf(LIGHT_RAIN);
    }

    /** The drawable that replaces a private-use glyph from {@link #symbol}, or 0 for other characters. */
    public static int glyphDrawable(char glyph) {
        switch (glyph) {
            case LIGHT_RAIN: return R.drawable.ic_rain_light;
            case MODERATE_RAIN: return R.drawable.ic_rain_moderate;
            case HEAVY_RAIN: return R.drawable.ic_rain_heavy;
            case FOG: return R.drawable.ic_fog;
            case SNOW: return R.drawable.ic_snow;
            case RIME_FOG: return R.drawable.ic_rime_fog;
            case LIGHT_DRIZZLE: return R.drawable.ic_drizzle_light;
            case MODERATE_DRIZZLE: return R.drawable.ic_drizzle_moderate;
            case HEAVY_DRIZZLE: return R.drawable.ic_drizzle_heavy;
            case CLOUDY_NIGHT: return R.drawable.ic_cloudy_night;
            case CLOUDY_DAY: return R.drawable.ic_cloudy_day;
            case MOSTLY_CLEAR_NIGHT: return R.drawable.ic_mostly_clear_night;
            case MOSTLY_CLEAR_DAY: return R.drawable.ic_mostly_clear_day;
            case LIGHT_SNOW: return R.drawable.ic_snow_light;
            case MODERATE_SNOW: return R.drawable.ic_snow_moderate;
            case SNOW_GRAINS: return R.drawable.ic_snow_grains;
            case LIGHT_FREEZING_DRIZZLE: return R.drawable.ic_freezing_drizzle_light;
            case HEAVY_FREEZING_DRIZZLE: return R.drawable.ic_freezing_drizzle_heavy;
            case LIGHT_SHOWERS: return R.drawable.ic_showers_light;
            case MODERATE_SHOWERS: return R.drawable.ic_showers_moderate;
            case HEAVY_SHOWERS: return R.drawable.ic_showers_heavy;
            case LIGHT_FREEZING_RAIN: return R.drawable.ic_freezing_rain_light;
            case HEAVY_FREEZING_RAIN: return R.drawable.ic_freezing_rain_heavy;
            case LIGHT_SNOW_SHOWERS: return R.drawable.ic_snow_showers_light;
            case HEAVY_SNOW_SHOWERS: return R.drawable.ic_snow_showers_heavy;
            case THUNDERSTORM: return R.drawable.ic_thunderstorm;
            case THUNDERSTORM_LIGHT_HAIL: return R.drawable.ic_thunderstorm_light_hail;
            case THUNDERSTORM_HEAVY_HAIL: return R.drawable.ic_thunderstorm_heavy_hail;
            default: return 0;
        }
    }

    public static String description(Context context, int code) {
        return context.getString(descriptionRes(code));
    }

    static int descriptionRes(int code) {
        switch (code) {
            case 0: return R.string.clear_weather;
            case 1: return R.string.mostly_clear;
            case 2: return R.string.partly_cloudy;
            case 3: return R.string.cloudy;
            case 45: return R.string.fog;
            case 48: return R.string.rime_fog;
            case 51: return R.string.light_drizzle;
            case 53: return R.string.moderate_drizzle;
            case 55: return R.string.heavy_drizzle;
            case 56: return R.string.light_freezing_drizzle;
            case 57: return R.string.heavy_freezing_drizzle;
            case 61: return R.string.light_rain;
            case 63: return R.string.moderate_rain;
            case 65: return R.string.heavy_rain;
            case 66: return R.string.light_freezing_rain;
            case 67: return R.string.heavy_freezing_rain;
            case 71: return R.string.light_snow;
            case 73: return R.string.moderate_snow;
            case 75: return R.string.heavy_snow;
            case 77: return R.string.snow_grains;
            case 80: return R.string.light_showers;
            case 81: return R.string.moderate_showers;
            case 82: return R.string.heavy_showers;
            case 85: return R.string.light_snow_showers;
            case 86: return R.string.heavy_snow_showers;
            case 95: return R.string.thunderstorms;
            case 96: return R.string.thunderstorm_light_hail;
            case 99: return R.string.thunderstorm_heavy_hail;
            default: return R.string.conditions_unavailable;
        }
    }
}
