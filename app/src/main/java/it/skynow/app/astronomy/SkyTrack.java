package it.skynow.app.astronomy;

import it.skynow.app.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

public final class SkyTrack extends View {
    public static final int START_COLOR = 0xff228465;
    public static final int MAX_COLOR = 0xffbf751b;
    public static final int END_COLOR = 0xffb14d74;
    public static final int BRIGHTEST_COLOR = 0xff8061ba;
    public static final int NOW_COLOR = 0xff536ab4;
    private final List<? extends SkyTrajectory.Point> points;
    private final SkyTrajectory.Point start, peak, end, now, brightest;
    private final String[] compass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private float centerX, centerY, radius;
    private double aimAzimuth = Double.NaN;
    private double aimElevation = Double.NaN;

    public SkyTrack(Context context, List<? extends SkyTrajectory.Point> points,
                    SkyTrajectory.Point start, SkyTrajectory.Point peak, SkyTrajectory.Point end,
                    SkyTrajectory.Point now, SkyTrajectory.Point brightest, String[] compass) {
        super(context);
        this.points = points;
        this.start = start;
        this.peak = peak;
        this.end = end;
        this.now = now;
        this.brightest = brightest;
        this.compass = compass;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAim(double azimuth, double elevation) {
        if (Double.compare(aimAzimuth, azimuth) == 0 && Double.compare(aimElevation, elevation) == 0) return;
        aimAzimuth = azimuth;
        aimElevation = elevation;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        centerX = getWidth() / 2f;
        centerY = getHeight() / 2f;
        radius = Math.min(centerX, centerY) - 27 * density;
        if (radius <= 0) return;
        int accent = getContext().getColor(R.color.sky_accent);
        int muted = getContext().getColor(R.color.sky_muted);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(getContext().getColor(R.color.sky_subtle));
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor((muted & 0x00ffffff) | 0x55000000);
        for (int i = 1; i <= 3; i++) canvas.drawCircle(centerX, centerY, radius * i / 3, paint);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, paint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(muted);
        paint.setTextSize(12 * density);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < compass.length; i++) {
            double angle = Math.PI / 2 * i;
            float x = centerX + (radius + 14 * density) * (float) Math.sin(angle);
            float y = centerY - (radius + 14 * density) * (float) Math.cos(angle);
            canvas.drawText(compass[i], x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }
        paint.setTextSize(10 * density);
        canvas.drawText("90°", centerX + 12 * density, centerY - 4 * density, paint);
        canvas.drawText("60°", centerX + 12 * density, centerY - radius / 3 - 4 * density, paint);
        canvas.drawText("30°", centerX + 12 * density, centerY - radius * 2 / 3 - 4 * density, paint);

        path.rewind();
        boolean first = true;
        for (SkyTrajectory.Point sample : points) {
            if (sample.elevation < 0) {
                first = true;
                continue;
            }
            if (first) path.moveTo(x(sample), y(sample));
            else path.lineTo(x(sample), y(sample));
            first = false;
        }
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3 * density);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        marker(canvas, brightest, BRIGHTEST_COLOR, 8.5f);
        marker(canvas, start, START_COLOR, 4.5f);
        marker(canvas, peak, MAX_COLOR, 6.5f);
        marker(canvas, end, END_COLOR, 4.5f);
        marker(canvas, now, NOW_COLOR, 4.5f);
        drawDirection(canvas, accent);
        drawAim(canvas);
    }

    private void drawAim(Canvas canvas) {
        if (!Double.isFinite(aimAzimuth) || !Double.isFinite(aimElevation) || aimElevation < 0) return;
        double angle = Math.toRadians(aimAzimuth);
        float distance = radius * (float) Math.max(0, 1 - aimElevation / 90);
        canvas.save();
        canvas.translate(centerX + distance * (float) Math.sin(angle), centerY - distance * (float) Math.cos(angle));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * density);
        paint.setColor(getContext().getColor(R.color.sky_card));
        drawReticle(canvas);
        paint.setStrokeWidth(1.8f * density);
        paint.setColor(getContext().getColor(R.color.sky_text));
        drawReticle(canvas);
        canvas.restore();
    }

    private void drawReticle(Canvas canvas) {
        float inner = 4 * density;
        float outer = 12 * density;
        canvas.drawCircle(0, 0, 8 * density, paint);
        canvas.drawLine(-outer, 0, -inner, 0, paint);
        canvas.drawLine(inner, 0, outer, 0, paint);
        canvas.drawLine(0, -outer, 0, -inner, paint);
        canvas.drawLine(0, inner, 0, outer, paint);
    }

    private void drawDirection(Canvas canvas, int accent) {
        if (points.size() < 2 || (now != null && now.elevation < 0)) return;
        boolean issPass = now == null;
        int next = issPass ? points.size() / 2 : 1;
        if (!issPass) {
            while (next < points.size() - 1 && points.get(next).time < now.time) next++;
        }
        SkyTrajectory.Point before = points.get(next - 1);
        SkyTrajectory.Point after = points.get(next);
        float dx = x(after) - x(before);
        float dy = y(after) - y(before);
        if (Math.hypot(dx, dy) < 0.01) return;

        canvas.save();
        float anchorX = issPass ? (x(before) + x(after)) / 2 : x(now);
        float anchorY = issPass ? (y(before) + y(after)) / 2 : y(now);
        canvas.translate(anchorX, anchorY);
        canvas.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
        canvas.translate((issPass ? -2.5f : 8) * density, 0);
        path.rewind();
        path.moveTo(0, -4 * density);
        path.lineTo(5 * density, 0);
        path.lineTo(0, 4 * density);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(getContext().getColor(R.color.sky_subtle));
        paint.setStrokeWidth(4.5f * density);
        canvas.drawPath(path, paint);
        paint.setColor(issPass ? accent : NOW_COLOR);
        paint.setStrokeWidth(2.3f * density);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private float x(SkyTrajectory.Point sample) {
        return centerX + distance(sample) * (float) Math.sin(Math.toRadians(sample.azimuth));
    }

    private float y(SkyTrajectory.Point sample) {
        return centerY - distance(sample) * (float) Math.cos(Math.toRadians(sample.azimuth));
    }

    private float distance(SkyTrajectory.Point sample) {
        return radius * (float) Math.max(0, 1 - sample.elevation / 90);
    }

    private void marker(Canvas canvas, SkyTrajectory.Point sample, int color, float size) {
        if (sample == null || sample.elevation < 0) return;
        paint.setColor(color);
        canvas.drawCircle(x(sample), y(sample), size * density, paint);
    }
}
