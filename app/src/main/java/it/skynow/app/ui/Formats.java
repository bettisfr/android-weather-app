package it.skynow.app.ui;

import android.content.Context;
import it.skynow.app.R;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats readings in the units and clock style chosen in Settings. Immutable: recreate it when they change. */
public final class Formats {
    public static final String[] TEMPERATURE_UNITS = {"°C", "°F", "K"};
    public static final String[] WIND_UNITS = {"km/h", "mph", "m/s", "kn"};
    public static final String[] PRESSURE_UNITS = {"hPa", "mbar", "inHg", "mmHg"};
    private static final double[] WIND_FACTORS = {1, 0.621371, 1.0 / 3.6, 1.0 / 1.852};
    private static final double[] PRESSURE_FACTORS = {1, 1, 0.029529983, 0.750061683};

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US);
    private static final DateTimeFormatter CLOCK_24_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter HOUR_CLOCK = DateTimeFormatter.ofPattern("h a", Locale.US);

    private final Context context;
    /** 0 = °C, 1 = °F, 2 = K. */
    public final int temperatureUnit;
    public final boolean clock24;
    /** Index into {@link #WIND_UNITS}. */
    public final int windUnit;
    /** Index into {@link #PRESSURE_UNITS}. */
    public final int pressureUnit;

    public Formats(Context context, int temperatureUnit, boolean clock24, int windUnit, int pressureUnit) {
        this.context = context;
        this.temperatureUnit = temperatureUnit;
        this.clock24 = clock24;
        this.windUnit = windUnit;
        this.pressureUnit = pressureUnit;
    }

    // Locale

    public Locale appLocale() {
        return Locale.forLanguageTag(context.getString(R.string.app_language_tag));
    }

    public boolean italian() {
        return appLocale().equals(Locale.ITALY);
    }

    public String number(double value, String format) {
        return Double.isFinite(value) ? String.format(appLocale(), format, value) : "—";
    }

    public String percent(double value) {
        return Double.isFinite(value) ? number(value, "%.0f") + "%" : "—";
    }

    public DateTimeFormatter shortDate(boolean year) {
        String pattern = italian() ? "EEE d MMM" : "EEE, MMM d";
        if (year) pattern += italian() ? " yyyy" : ", yyyy";
        return DateTimeFormatter.ofPattern(pattern, appLocale());
    }

    // Temperature

    public String temp(double celsius) {
        double displayed = displayTemperature(celsius);
        return number(Math.abs(displayed) < 0.5 ? 0 : displayed, "%.0f") + temperatureSuffix();
    }

    public String tempDecimal(double celsius) {
        double displayed = displayTemperature(celsius);
        return number(Math.abs(displayed) < 0.05 ? 0 : displayed, "%.1f") + temperatureSuffix();
    }

    public String temperatureSuffix() {
        return temperatureUnit == 2 ? " K" : "°";
    }

    public double displayTemperature(double celsius) {
        if (temperatureUnit == 2) return celsius + 273.15;
        if (temperatureUnit == 1) return celsius * 1.8 + 32;
        return celsius;
    }

    // Wind and pressure

    public double displayWind(double kmh) {
        return kmh * WIND_FACTORS[windUnit];
    }

    public String windUnitLabel() {
        return WIND_UNITS[windUnit];
    }

    public String windSpeed(double kmh, boolean includeUnit) {
        String reading = number(displayWind(kmh), windUnit == 2 ? "%.1f" : "%.0f");
        return includeUnit && Double.isFinite(kmh) ? reading + " " + windUnitLabel() : reading;
    }

    public String pressureUnitLabel() {
        return PRESSURE_UNITS[pressureUnit];
    }

    public String pressureReading(double hpa) {
        return number(hpa * PRESSURE_FACTORS[pressureUnit], pressureUnit == 2 ? "%,.2f" : "%,.0f");
    }

    // Time

    public DateTimeFormatter clockFormat() {
        return clock24 ? CLOCK_24 : CLOCK;
    }

    public DateTimeFormatter secondsClockFormat() {
        return clock24 ? CLOCK_24_SECONDS : CLOCK_SECONDS;
    }

    /** Whole hours for chart axes: "14:00" or "2 PM". */
    public DateTimeFormatter hourClockFormat() {
        return clock24 ? CLOCK_24 : HOUR_CLOCK;
    }

    /** The "HH:mm" part of an ISO local timestamp. */
    public String clock(String timestamp) {
        return formatTime(localTime(timestamp), clockFormat());
    }

    /** Like {@link #clock}, with seconds when the timestamp has them. */
    public String eventClock(String timestamp) {
        return timestamp.length() < 19 ? clock(timestamp)
                : LocalTime.parse(timestamp.substring(11)).format(secondsClockFormat());
    }

    public String durationText(long minutes) {
        return minutes >= 60 ? context.getString(R.string.duration_hour_min, minutes / 60, minutes % 60)
                : context.getString(R.string.duration_minutes, minutes);
    }

    public static String formatTime(LocalTime time, DateTimeFormatter format) {
        return time == null ? "—" : time.format(format);
    }

    public static LocalTime localTime(String timestamp) {
        return timestamp.length() >= 16 ? LocalTime.parse(timestamp.substring(11, 16)) : null;
    }
}
