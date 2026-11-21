package it.meteo.locale.astronomy;

import it.meteo.locale.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class SkyPath extends View {
    private static final float MINUTES_PER_DAY = 24 * 60;
    // Mean interval between successive lunar transits.
    private static final float LUNAR_CYCLE_MINUTES = 24 * 60 + 50;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float nowMinute;
    private final float riseMinute;
    private final float setMinute;
    private final float cycleMinutes;
    private final float polarHorizon;

    public SkyPath(Context context, LocalTime sunrise, LocalTime sunset, LocalTime time) {
        this(context, minutes(sunrise), minutes(sunset), time == null ? Float.NaN : minutes(time), MINUTES_PER_DAY, Float.NaN);
    }

    public static SkyPath moon(Context context, long rise, long set, long now) {
        float nextSet = set > rise ? set : set + LUNAR_CYCLE_MINUTES;
        if (nextSet <= rise || nextSet - rise >= LUNAR_CYCLE_MINUTES) return null;
        return new SkyPath(context, rise, nextSet, now, LUNAR_CYCLE_MINUTES, Float.NaN);
    }

    public static SkyPath polarSun(Context context, LocalTime noon, LocalTime time, boolean daylight,
                            double noonElevation, double midnightElevation) {
        double center = (noonElevation + midnightElevation) / 2;
        double amplitude = Math.max(0.1, (noonElevation - midnightElevation) / 2);
        float horizon = (float) Math.max(1.2, Math.abs(center) / amplitude);
        float midpoint = minutes(noon);
        return new SkyPath(context, midpoint, midpoint, minutes(time), MINUTES_PER_DAY,
                daylight ? horizon : -horizon);
    }

    private SkyPath(Context context, float rise, float set, float now, float cycleMinutes, float polarHorizon) {
        super(context);
        riseMinute = rise;
        setMinute = set;
        nowMinute = now;
        this.cycleMinutes = cycleMinutes;
        this.polarHorizon = polarHorizon;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 5 * density;
        float width = getWidth() - 10 * density;
        boolean polar = Float.isFinite(polarHorizon);
        if (width <= 0 || (!polar && (riseMinute < 0 || setMinute <= riseMinute
                || setMinute - riseMinute >= cycleMinutes))) return;
        boolean lunar = cycleMinutes == LUNAR_CYCLE_MINUTES;
        float center = getHeight() * .5f;
        float amplitude = getHeight() * .35f;
        float daylightFraction = (setMinute - riseMinute) / cycleMinutes;
        float sunrisePhase = .5f - daylightFraction / 2;
        float sunsetPhase = .5f + daylightFraction / 2;
        float horizon = curveY(center, amplitude, sunrisePhase);
        if (polar) {
            amplitude /= Math.max(1, Math.abs(polarHorizon) / 1.25f);
            horizon = center + amplitude * polarHorizon;
            sunrisePhase = 0;
            sunsetPhase = polarHorizon > 0 ? 1 : 0;
        }
        float midpoint = (riseMinute + setMinute) / 2f;
        float phase = .5f + (nowMinute - midpoint) / cycleMinutes;
        if (lunar || polar) phase -= (float) Math.floor(phase);
        float elapsed = Math.max(0, Math.min(1, phase));

        fill(canvas, left, width, center, amplitude, horizon, sunrisePhase, sunsetPhase, lunar ? getContext().getColor(R.color.sky_path_moon_future) : getContext().getColor(R.color.sky_path_sun_future));
        if (Float.isFinite(nowMinute)) {
            fill(canvas, left, width, center, amplitude, horizon, 0, Math.min(sunrisePhase, elapsed), getContext().getColor(R.color.sky_path_below));
            fill(canvas, left, width, center, amplitude, horizon, sunrisePhase, Math.min(sunsetPhase, elapsed),
                    lunar ? getContext().getColor(R.color.sky_path_moon_elapsed) : getContext().getColor(R.color.sky_path_sun_elapsed));
            fill(canvas, left, width, center, amplitude, horizon, sunsetPhase, elapsed, getContext().getColor(R.color.sky_path_below));
        }
        curve(left, width, center, amplitude, 0, 1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(getContext().getColor(R.color.sky_path_outline));
        canvas.drawPath(path, paint);
        canvas.drawLine(left, horizon, left + width, horizon, paint);
        if (Float.isFinite(nowMinute) && phase >= 0 && phase <= 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((polar ? polarHorizon > 0 : phase >= sunrisePhase && phase <= sunsetPhase)
                    ? (lunar ? getContext().getColor(R.color.sky_accent) : getContext().getColor(R.color.sky_path_sun_dot)) : getContext().getColor(R.color.sky_path_below));
            canvas.drawCircle(left + width * phase, curveY(center, amplitude, phase), 4.5f * density, paint);
        }
    }

    private void fill(Canvas canvas, float left, float width, float center, float amplitude, float horizon, float start, float end, int color) {
        if (end <= start) return;
        curve(left, width, center, amplitude, start, end);
        path.lineTo(left + width * end, horizon);
        path.lineTo(left + width * start, horizon);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    private void curve(float left, float width, float center, float amplitude, float start, float end) {
        path.reset();
        path.moveTo(left + width * start, curveY(center, amplitude, start));
        for (int i = 1; i <= 64; i++) {
            float phase = start + (end - start) * i / 64f;
            path.lineTo(left + width * phase, curveY(center, amplitude, phase));
        }
    }

    private static float curveY(float center, float amplitude, float phase) {
        double elevation = -Math.cos(phase * 2 * Math.PI);
        return center - amplitude * (float) elevation;
    }

    public static int civilTwilightMinutes(LocalDate date, double latitude) {
        // NOAA general solar position equations, evaluated near local solar noon.
        double yearAngle = yearAngle(date);
        double declination = 0.006918 - 0.399912 * Math.cos(yearAngle) + 0.070257 * Math.sin(yearAngle)
                - 0.006758 * Math.cos(2 * yearAngle) + 0.000907 * Math.sin(2 * yearAngle)
                - 0.002697 * Math.cos(3 * yearAngle) + 0.00148 * Math.sin(3 * yearAngle);
        double lat = Math.toRadians(latitude);
        double offset = Math.sin(lat) * Math.sin(declination);
        double scale = Math.cos(lat) * Math.cos(declination);
        double rise = Math.acos((Math.cos(Math.toRadians(90.833)) - offset) / scale);
        double twilight = Math.acos((Math.cos(Math.toRadians(96)) - offset) / scale);
        double duration = 4 * Math.toDegrees(twilight - rise);
        return Double.isFinite(duration) ? (int) Math.round(duration) : -1;
    }

    public static LocalTime solarNoon(LocalDate date, double longitude, ZoneId zone) {
        double angle = yearAngle(date);
        double equationOfTime = 229.18 * (0.000075 + 0.001868 * Math.cos(angle)
                - 0.032077 * Math.sin(angle) - 0.014615 * Math.cos(2 * angle)
                - 0.040849 * Math.sin(2 * angle));
        double localOffset = date.atTime(LocalTime.NOON).atZone(zone).getOffset().getTotalSeconds() / 60.0;
        long localMinutes = Math.round(720 - 4 * longitude - equationOfTime + localOffset);
        return LocalTime.MIDNIGHT.plusMinutes(localMinutes);
    }

    private static double yearAngle(LocalDate date) {
        return 2 * Math.PI * (date.getDayOfYear() - 1) / date.lengthOfYear();
    }

    private static int minutes(LocalTime time) {
        return time == null ? -1 : time.toSecondOfDay() / 60;
    }
}
