package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class HumidityGauge extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF track = new RectF();
    private final double humidity;

    HumidityGauge(Context context, double humidity) {
        super(context);
        this.humidity = humidity;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float center = getWidth() / 2f;
        float radius = 9 * density;
        track.set(center - radius, 15 * density, center + radius, getHeight() - 15 * density);
        paint.setColor(0xfff7ecd9);
        canvas.drawRoundRect(track, radius, radius, paint);
        if (Double.isFinite(humidity)) {
            float level = track.bottom - track.height() * (float) Math.max(0, Math.min(100, humidity)) / 100;
            clip.reset();
            clip.addRoundRect(track, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setColor(0xffcf8129);
            canvas.drawRect(track.left, level, track.right, track.bottom, paint);
            canvas.restore();
            paint.setColor(0xff774e24);
            canvas.drawLine(track.left - 5 * density, level, track.left + 3 * density, level, paint);
        }
        paint.setColor(0xff526a63);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(10 * density);
        canvas.drawText("100", center, 11 * density, paint);
        canvas.drawText("0", center, getHeight() - 2 * density, paint);
    }
}
