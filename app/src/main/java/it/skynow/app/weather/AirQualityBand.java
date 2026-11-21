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
}
