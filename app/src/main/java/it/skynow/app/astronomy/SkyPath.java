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
    private static final int SAMPLES = 96;
    private static final float MIN_ELEVATION_RANGE = 10;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path outline = new Path();
    private final float[] elevations = new float[SAMPLES + 1];
    private final float nowFraction;
    private final float nowElevation;
    private final boolean moon;

    public SkyPath(Context context, LocalDate date, ZoneId zone, Instant now,
                   double latitude, double longitude, boolean moon) {
        super(context);
        this.moon = moon;
        long start = date.atStartOfDay(zone).toEpochSecond();
        long span = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - start;
        nowFraction = (now.getEpochSecond() - start) / (float) span;
        nowElevation = elevation(now, latitude, longitude, moon);
        for (int i = 0; i <= SAMPLES; i++) {
            Instant time = Instant.ofEpochSecond(start + span * i / SAMPLES);
            elevations[i] = elevation(time, latitude, longitude, moon);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static float elevation(Instant time, double latitude, double longitude, boolean moon) {
        return (float) (90 - SkyPosition.at(time, latitude, longitude, moon).zenith);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 5 * density;
        float width = getWidth() - 2 * left;
        if (width <= 0) return;
        float low = 0;
        float high = 0;
        for (float elevation : elevations) {
            low = Math.min(low, elevation);
            high = Math.max(high, elevation);
        }
        float scale = getHeight() * .76f / Math.max(MIN_ELEVATION_RANGE, high - low);
        float horizon = getHeight() / 2f + (low + high) * scale / 2;
        float nowX = left + width * nowFraction;
        float nowY = horizon - nowElevation * scale;
        int future = getContext().getColor(moon ? R.color.sky_path_moon_future : R.color.sky_path_sun_future);
        int above = getContext().getColor(moon ? R.color.sky_path_moon_elapsed : R.color.sky_path_sun_elapsed);
        int below = getContext().getColor(R.color.sky_path_below);
        outline.reset();
        float x = left;
        float y = horizon - elevations[0] * scale;
        outline.moveTo(x, y);
        for (int i = 1; i <= SAMPLES; i++) {
            float nextX = left + width * i / SAMPLES;
            float nextY = horizon - elevations[i] * scale;
            if (x < nowX && nowX < nextX) {
                fillSegment(canvas, x, y, nowX, nowY, horizon, above, below);
                outline.lineTo(nowX, nowY);
                x = nowX;
                y = nowY;
            }
            boolean past = nextX <= nowX;
            fillSegment(canvas, x, y, nextX, nextY, horizon, past ? above : future,
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
        if (nowFraction >= 0 && nowFraction < 1) {
            paint.setStyle(Paint.Style.FILL);
            int dot = getContext().getColor(moon ? R.color.sky_accent : R.color.sky_path_sun_dot);
            paint.setColor(nowElevation > 0 ? dot : below);
            canvas.drawCircle(nowX, nowY, 4.5f * density, paint);
        }
    }

    private void fillSegment(Canvas canvas, float x, float y, float endX, float endY,
                                 float horizon, int above, int below) {
        boolean startsAbove = y <= horizon;
        boolean endsAbove = endY <= horizon;
        if (startsAbove == endsAbove) {
            fillArea(canvas, x, y, endX, endY, horizon, startsAbove ? above : below);
            return;
        }
        float crossing = x + (endX - x) * (horizon - y) / (endY - y);
        fillArea(canvas, x, y, crossing, horizon, horizon, startsAbove ? above : below);
        fillArea(canvas, crossing, horizon, endX, endY, horizon, endsAbove ? above : below);
    }

    private void fillArea(Canvas canvas, float x, float y, float endX, float endY, float horizon, int color) {
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

}
