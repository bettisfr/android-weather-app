package it.skynow.app.weather;

import it.skynow.app.R;
import it.skynow.app.ui.SignalColors;

public enum AirQualityBand {
    GOOD(20, SignalColors.GREEN, R.string.aqi_good),
    FAIR(40, SignalColors.LIME, R.string.aqi_fair),
    MODERATE(60, SignalColors.YELLOW, R.string.aqi_moderate),
    POOR(80, SignalColors.RED, R.string.aqi_poor),
    VERY_POOR(100, SignalColors.DEEP_RED, R.string.aqi_very_poor),
    EXTREME(Integer.MAX_VALUE, SignalColors.PURPLE, R.string.aqi_extreme);

    private static final AirQualityBand[] BANDS = values();
    public final int upper;
    public final int color;
    public final int label;

    AirQualityBand(int upper, int color, int label) {
        this.upper = upper;
        this.color = color;
        this.label = label;
    }

    public static AirQualityBand forIndex(long index) {
        for (AirQualityBand band : BANDS) {
            if (index <= band.upper) return band;
        }
        return EXTREME;
    }

    // Open-Meteo's European AQI bands for hourly concentrations in µg/m³.
    public static AirQualityBand forConcentration(String pollutant, double value) {
        if (!Double.isFinite(value) || value < 0) return null;
        int[] thresholds;
        switch (pollutant) {
            case "pm2_5": thresholds = new int[]{5, 15, 50, 90, 140}; break;
            case "pm10": thresholds = new int[]{15, 45, 120, 195, 270}; break;
            case "ozone": thresholds = new int[]{60, 100, 120, 160, 180}; break;
            case "nitrogen_dioxide": thresholds = new int[]{10, 25, 60, 100, 150}; break;
            case "sulphur_dioxide": thresholds = new int[]{20, 40, 125, 190, 275}; break;
            default: return null;
        }
        for (int i = 0; i < thresholds.length; i++) {
            if (value <= thresholds[i]) return BANDS[i];
        }
        return EXTREME;
    }
}
