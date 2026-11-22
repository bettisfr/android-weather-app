package it.skynow.app.astronomy;

import it.skynow.app.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class SkyPath extends View {
    private static final float MINUTES_PER_DAY = 24 * 60;
    private static final int MOON_SAMPLES = 96;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path outline = new Path();
    private final float nowMinute;
    private final float riseMinute;
    private final float setMinute;
    private final float cycleMinutes;
    private final float polarHorizon;
    private float[] lunarElevations;
    private float lunarNowElevation;

    public SkyPath(Context context, LocalTime sunrise, LocalTime sunset, LocalTime time) {
        this(context, minutes(sunrise), minutes(sunset), time == null ? Float.NaN : minutes(time), MINUTES_PER_DAY, Float.NaN);
    }

    public static SkyPath moon(Context context, LocalDate date, ZoneId zone, Instant now,
                               double latitude, double longitude) {
        long start = date.atStartOfDay(zone).toEpochSecond();
        long span = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - start;
        SkyPath chart = new SkyPath(context, 0, 0, now.getEpochSecond() - start, span, Float.NaN);
        chart.lunarElevations = new float[MOON_SAMPLES + 1];
        for (int i = 0; i <= MOON_SAMPLES; i++) {
            Instant time = Instant.ofEpochSecond(start + span * i / MOON_SAMPLES);
            chart.lunarElevations[i] = moonElevation(time, latitude, longitude);
        }
        chart.lunarNowElevation = moonElevation(now, latitude, longitude);
        return chart;
    }

    private static float moonElevation(Instant time, double latitude, double longitude) {
        return (float) (90 - SkyPosition.at(time, latitude, longitude, true).zenith);
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
        if (width <= 0) return;
        if (lunarElevations != null) {
            drawMoon(canvas, left, width, density);
            return;
        }
        boolean polar = Float.isFinite(polarHorizon);
        if (!polar && (riseMinute < 0 || setMinute <= riseMinute
                || setMinute - riseMinute >= cycleMinutes)) return;
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
        if (polar) phase -= (float) Math.floor(phase);
        float elapsed = Math.max(0, Math.min(1, phase));

        fill(canvas, left, width, center, amplitude, horizon, sunrisePhase, sunsetPhase, getContext().getColor(R.color.sky_path_sun_future));
        if (Float.isFinite(nowMinute)) {
            fill(canvas, left, width, center, amplitude, horizon, 0, Math.min(sunrisePhase, elapsed), getContext().getColor(R.color.sky_path_below));
            fill(canvas, left, width, center, amplitude, horizon, sunrisePhase, Math.min(sunsetPhase, elapsed),
                    getContext().getColor(R.color.sky_path_sun_elapsed));
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
                    ? getContext().getColor(R.color.sky_path_sun_dot) : getContext().getColor(R.color.sky_path_below));
            canvas.drawCircle(left + width * phase, curveY(center, amplitude, phase), 4.5f * density, paint);
        }
    }

    private void drawMoon(Canvas canvas, float left, float width, float density) {
        float low = 0;
        float high = 0;
        for (float elevation : lunarElevations) {
            low = Math.min(low, elevation);
            high = Math.max(high, elevation);
        }
        float scale = getHeight() * .76f / Math.max(1, high - low);
        float horizon = getHeight() / 2f + (low + high) * scale / 2;
        float elapsed = nowMinute / cycleMinutes;
        float nowX = left + width * elapsed;
        float nowY = horizon - lunarNowElevation * scale;
        int future = getContext().getColor(R.color.sky_path_moon_future);
        int above = getContext().getColor(R.color.sky_path_moon_elapsed);
        int below = getContext().getColor(R.color.sky_path_below);
        outline.reset();
        float x = left;
        float y = horizon - lunarElevations[0] * scale;
        outline.moveTo(x, y);
        for (int i = 1; i <= MOON_SAMPLES; i++) {
            float nextX = left + width * i / MOON_SAMPLES;
            float nextY = horizon - lunarElevations[i] * scale;
            if (x < nowX && nowX < nextX) {
                fillMoonSegment(canvas, x, y, nowX, nowY, horizon, above, below);
                outline.lineTo(nowX, nowY);
                x = nowX;
                y = nowY;
            }
            boolean past = nextX <= nowX;
            fillMoonSegment(canvas, x, y, nextX, nextY, horizon, past ? above : future,
                    past ? below : android.graphics.Color.TRANSPARENT);
            outline.lineTo(nextX, nextY);
            x = nextX;
            y = nextY;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(getContext().getColor(R.color.sky_path_outline));
        canvas.drawPath(outline, paint);
        canvas.drawLine(left, horizon, left + width, horizon, paint);
        if (elapsed >= 0 && elapsed <= 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lunarNowElevation >= 0 ? getContext().getColor(R.color.sky_accent) : below);
            canvas.drawCircle(nowX, nowY, 4.5f * density, paint);
        }
    }

    private void fillMoonSegment(Canvas canvas, float x, float y, float endX, float endY,
                                 float horizon, int above, int below) {
        boolean startsAbove = y <= horizon;
        boolean endsAbove = endY <= horizon;
        if (startsAbove == endsAbove) {
            fillMoonArea(canvas, x, y, endX, endY, horizon, startsAbove ? above : below);
            return;
        }
        float crossing = x + (endX - x) * (horizon - y) / (endY - y);
        fillMoonArea(canvas, x, y, crossing, horizon, horizon, startsAbove ? above : below);
        fillMoonArea(canvas, crossing, horizon, endX, endY, horizon, endsAbove ? above : below);
    }

    private void fillMoonArea(Canvas canvas, float x, float y, float endX, float endY, float horizon, int color) {
        if (color == android.graphics.Color.TRANSPARENT) return;
        path.reset();
        path.moveTo(x, horizon);
        path.lineTo(x, y);
        path.lineTo(endX, endY);
        path.lineTo(endX, horizon);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(path, paint);
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
