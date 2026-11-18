package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class SunPath extends View {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int nowMinute;
    private final int riseMinute;
    private final int setMinute;
    private final int twilightMinutes;

    SunPath(Context context, String sunrise, String sunset, String time, int twilightMinutes) {
        super(context);
        nowMinute = minutes(time);
        riseMinute = minutes(sunrise);
        setMinute = minutes(sunset);
        this.twilightMinutes = twilightMinutes;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 5 * density;
        float width = getWidth() - 10 * density;
        if (riseMinute < 0 || setMinute <= riseMinute || width <= 0) return;
        float horizon = getHeight() * .62f;
        float amplitude = Math.min(horizon, getHeight() - horizon) * .69f;
        float progress = (float) (nowMinute - riseMinute) / (setMinute - riseMinute);
        float phase = .25f + .5f * progress;
        if (twilightMinutes > 0) {
            if (nowMinute < riseMinute) phase = .25f + .25f * (nowMinute - riseMinute) / twilightMinutes;
            if (nowMinute > setMinute) phase = .75f + .25f * (nowMinute - setMinute) / twilightMinutes;
        }
        float elapsed = Math.max(0, Math.min(1, phase));

        fill(canvas, left, width, horizon, amplitude, .25f, .75f, 0xfff8efdc);
        if (nowMinute >= 0) {
            fill(canvas, left, width, horizon, amplitude, 0, Math.min(.25f, elapsed), 0xff344a65);
            fill(canvas, left, width, horizon, amplitude, .25f, Math.min(.75f, elapsed), 0xffedc779);
            fill(canvas, left, width, horizon, amplitude, .75f, elapsed, 0xff344a65);
        }
        curve(left, width, horizon, amplitude, 0, 1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0xff8a96a5);
        canvas.drawPath(path, paint);
        canvas.drawLine(left, horizon, left + width, horizon, paint);
        if (nowMinute >= 0 && phase >= 0 && phase <= 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(progress >= 0 && progress <= 1 ? 0xffbe7d1e : 0xff344a65);
            canvas.drawCircle(left + width * phase, curveY(horizon, amplitude, phase), 4.5f * density, paint);
        }
    }

    private void fill(Canvas canvas, float left, float width, float horizon, float amplitude, float start, float end, int color) {
        if (end <= start) return;
        curve(left, width, horizon, amplitude, start, end);
        path.lineTo(left + width * end, horizon);
        path.lineTo(left + width * start, horizon);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    private void curve(float left, float width, float horizon, float amplitude, float start, float end) {
        path.reset();
        path.moveTo(left + width * start, curveY(horizon, amplitude, start));
        for (int i = 1; i <= 64; i++) {
            float phase = start + (end - start) * i / 64f;
            path.lineTo(left + width * phase, curveY(horizon, amplitude, phase));
        }
    }

    private static float curveY(float horizon, float amplitude, float phase) {
        double elevation = -Math.cos(phase * 2 * Math.PI);
        return horizon - amplitude * (float) elevation;
    }

    static int civilTwilightMinutes(LocalDate date, double latitude) {
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

    static String solarNoon(LocalDate date, double longitude, ZoneId zone) {
        double angle = yearAngle(date);
        double equationOfTime = 229.18 * (0.000075 + 0.001868 * Math.cos(angle)
                - 0.032077 * Math.sin(angle) - 0.014615 * Math.cos(2 * angle)
                - 0.040849 * Math.sin(2 * angle));
        double localOffset = date.atTime(LocalTime.NOON).atZone(zone).getOffset().getTotalSeconds() / 60.0;
        long localMinutes = Math.round(720 - 4 * longitude - equationOfTime + localOffset);
        return LocalTime.MIDNIGHT.plusMinutes(localMinutes).format(CLOCK);
    }

    private static double yearAngle(LocalDate date) {
        return 2 * Math.PI * (date.getDayOfYear() - 1) / date.lengthOfYear();
    }

    static String offsetTime(String time, int offsetMinutes) {
        int minute = minutes(time);
        if (minute < 0) return "—";
        return LocalTime.MIDNIGHT.plusMinutes(minute + offsetMinutes).format(CLOCK);
    }

    private static int minutes(String time) {
        try {
            return LocalTime.parse(time, CLOCK).toSecondOfDay() / 60;
        } catch (java.time.DateTimeException ignored) {
            return -1;
        }
    }
}
