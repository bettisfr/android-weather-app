package it.skynow.app.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class ForecastCache {
    public final JSONObject forecast;
    public final Instant updatedAt;
    public final double latitude;
    public final double longitude;
    public final String place;
    public final String country;
    public final String region;

    private ForecastCache(JSONObject forecast, Instant updatedAt, SharedPreferences storage) {
        this.forecast = forecast;
        this.updatedAt = updatedAt;
        latitude = Double.longBitsToDouble(storage.getLong("latitude", 0));
        longitude = Double.longBitsToDouble(storage.getLong("longitude", 0));
        place = storage.getString("place", "");
        country = storage.getString("country", "");
        region = storage.getString("region", "");
    }

    public static ForecastCache read(Context context) {
        SharedPreferences storage = storage(context);
        String serialized = storage.getString("forecast", null);
        if (serialized == null) return null;
        try {
            JSONObject forecast = new JSONObject(serialized);
            ZoneId zone = ZoneId.of(forecast.getString("timezone"));
            LocalDate firstDay = LocalDate.parse(forecast.getJSONObject("daily").getJSONArray("time").getString(0));
            // The first row is labeled Today, so keep only a snapshot for the current local day.
            if (!firstDay.equals(LocalDate.now(zone))) return null;
            return new ForecastCache(forecast, Instant.ofEpochMilli(storage.getLong("updatedAt", 0)), storage);
        } catch (JSONException | java.time.DateTimeException invalid) {
            return null;
        }
    }

    public static void write(Context context, String forecast, Instant updatedAt, double latitude, double longitude,
                      String place, String country, String region) {
        storage(context).edit().putString("forecast", forecast).putLong("updatedAt", updatedAt.toEpochMilli())
                .putLong("latitude", Double.doubleToLongBits(latitude)).putLong("longitude", Double.doubleToLongBits(longitude))
                .putString("place", place).putString("country", country).putString("region", region).apply();
    }

    public boolean matches(double latitude, double longitude, boolean currentLocation) {
        if (!currentLocation) return this.latitude == latitude && this.longitude == longitude;
        float[] distance = new float[1];
        Location.distanceBetween(this.latitude, this.longitude, latitude, longitude, distance);
        return distance[0] <= 1000;
    }

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("last_forecast", Context.MODE_PRIVATE);
    }
}
