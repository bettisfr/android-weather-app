package it.meteo.locale.weather.views;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

public final class WeatherIconSpan extends ReplacementSpan {
    private final Drawable icon;

    public WeatherIconSpan(Drawable icon) {
        this.icon = icon;
    }

    @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
        if (metrics != null) {
            paint.getFontMetricsInt(metrics);
            int iconTop = iconTop(metrics);
            int iconBottom = iconTop + icon.getBounds().height();
            metrics.ascent = Math.min(metrics.ascent, iconTop);
            metrics.descent = Math.max(metrics.descent, iconBottom);
            metrics.top = Math.min(metrics.top, metrics.ascent);
            metrics.bottom = Math.max(metrics.bottom, metrics.descent);
        }
        return icon.getBounds().width();
    }

    @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
            int top, int baseline, int bottom, Paint paint) {
        int saved = canvas.save();
        canvas.translate(x, baseline + iconTop(paint.getFontMetricsInt()));
        icon.draw(canvas);
        canvas.restoreToCount(saved);
    }

    private int iconTop(Paint.FontMetricsInt metrics) {
        return Math.floorDiv(metrics.ascent + metrics.descent - icon.getBounds().height(), 2);
    }
}
