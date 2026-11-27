package it.skynow.app.weather;

import static it.skynow.app.weather.ForecastSeries.length;
import static it.skynow.app.weather.ForecastSeries.text;
import static it.skynow.app.weather.ForecastSeries.value;

import org.json.JSONObject;

/** The representative WMO weather code of one local day, plus its most notable other event. */
public final class DailyWeather {
    public final int code;
    public final boolean night;
    public final int eventCode;
    public final String eventTime;

    DailyWeather(int code, boolean night, int eventCode, String eventTime) {
        this.code = code;
        this.night = night;
        this.eventCode = eventCode;
        this.eventTime = eventTime;
    }

    /**
     * Picks the most frequent daylight code for {@code date} (every hour when the day has no
     * daylight) and the most severe event of a different kind from {@code eventStart} onwards.
     */
    public static DailyWeather summarize(JSONObject hourly, int eventStart, String date, int fallback) {
        int start = 0;
        int size = length(hourly);
        while (start < size && !text(hourly, "time", start).startsWith(date)) start++;
        int end = start;
        int[] counts = new int[100];
        int[] daylight = new int[100];
        boolean hasDaylight = false;
        boolean allNight = true;
        while (end < size && text(hourly, "time", end).startsWith(date)) {
            double rawCode = value(hourly, "weather_code", end);
            double isDay = value(hourly, "is_day", end++);
            if (!Double.isFinite(rawCode) || rawCode < 0 || rawCode >= counts.length) continue;
            int code = (int) rawCode;
            counts[code]++;
            allNight &= isDay == 0;
            if (isDay == 1) {
                daylight[code]++;
                hasDaylight = true;
            }
        }

        int[] preferred = hasDaylight ? daylight : counts;
        int predominant = fallback;
        int highestCount = 0;
        for (int code = 0; code < preferred.length; code++) {
            if (preferred[code] > highestCount) {
                predominant = code;
                highestCount = preferred[code];
            }
        }

        int eventCode = 0;
        int eventGroup = 0;
        String eventTime = null;
        int predominantGroup = eventGroup(predominant);
        for (int hour = Math.max(start, eventStart); hour < end; hour++) {
            int code = (int) value(hourly, "weather_code", hour);
            int group = eventGroup(code);
            if (group != 0 && group != predominantGroup
                    && (group > eventGroup || (group == eventGroup && code > eventCode))) {
                eventGroup = group;
                eventCode = code;
                eventTime = text(hourly, "time", hour);
            }
        }
        return new DailyWeather(predominant, highestCount > 0 && allNight, eventCode, eventTime);
    }

    /** Severity rank: thunderstorm, freezing precipitation, snow, rain or drizzle, fog, none. */
    static int eventGroup(int code) {
        if (code >= 95) return 5;
        if (code == 56 || code == 57 || code == 66 || code == 67) return 4;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 3;
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) return 2;
        if (code == 45 || code == 48) return 1;
        return 0;
    }

    /** Highest finite reading of {@code key} from hour {@code start} to the end of {@code date}. */
    public static double remainingMaximum(JSONObject hourly, String key, int start, String date) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = start; i < length(hourly) && text(hourly, "time", i).startsWith(date); i++) {
            double candidate = value(hourly, key, i);
            if (Double.isFinite(candidate)) maximum = Math.max(maximum, candidate);
        }
        return maximum;
    }

    /** Daily maximum ({@code high}) or mean of the finite hourly readings of {@code key}. */
    public static double hourlySummary(JSONObject hourly, String key, String date, boolean high) {
        int count = 0;
        double total = 0;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < length(hourly); i++) {
            if (!text(hourly, "time", i).startsWith(date)) continue;
            double reading = value(hourly, key, i);
            if (!Double.isFinite(reading)) continue;
            count++;
            total += reading;
            maximum = Math.max(maximum, reading);
        }
        if (count == 0) return Double.NaN;
        return high ? maximum : total / count;
    }
}
