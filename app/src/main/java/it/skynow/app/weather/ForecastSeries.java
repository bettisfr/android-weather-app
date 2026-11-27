package it.skynow.app.weather;

import org.json.JSONArray;
import org.json.JSONObject;

/** Null-tolerant accessors for Open-Meteo's column-oriented hourly and daily series. */
public final class ForecastSeries {
    private ForecastSeries() {}

    public static int length(JSONObject series) {
        return series.optJSONArray("time").length();
    }

    public static String text(JSONObject series, String key, int index) {
        return series.optJSONArray(key).optString(index);
    }

    public static double value(JSONObject series, String key, int index) {
        JSONArray array = series.optJSONArray(key);
        return array == null ? Double.NaN : array.optDouble(index);
    }
}
