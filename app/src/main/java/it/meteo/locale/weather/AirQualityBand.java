package it.meteo.locale.weather;

import it.meteo.locale.R;

public enum AirQualityBand {
    GOOD(20, 0xff48c8bf, R.string.aqi_good),
    FAIR(40, 0xff65b878, R.string.aqi_fair),
    MODERATE(60, 0xffe6c646, R.string.aqi_moderate),
    POOR(80, 0xffee7465, R.string.aqi_poor),
    VERY_POOR(100, 0xffaa3e58, R.string.aqi_very_poor),
    EXTREME(Integer.MAX_VALUE, 0xff8550a0, R.string.aqi_extreme);

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
