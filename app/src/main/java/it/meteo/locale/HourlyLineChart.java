package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;

final class HourlyLineChart extends View {
    private final int primary;
    private final int secondary;
    private final int grid;
    private final int muted;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Shader primaryGradient;
    private Shader secondaryGradient;
    private final DashPathEffect dashed;
    private final float density;
    private final float readingTextSize;
    private final double[] first;
    private final double[] second;
    private final int hourWidth;
    private final double low;
    private final double high;
    private final boolean hasReadings;
    private final boolean precipitation;
    private final Locale locale;
    private final int decimals;

    HourlyLineChart(Context context, double[] first, double[] second, int hourWidth, boolean precipitation, boolean humidity, boolean wind, int decimals) {
        super(context);
        primary = context.getColor(R.color.sky_accent);
        secondary = context.getColor(R.color.sky_chart_secondary);
        grid = context.getColor(R.color.sky_grid);
        muted = context.getColor(R.color.sky_muted);
        this.first = first;
        this.second = second;
        this.hourWidth = hourWidth;
        this.precipitation = precipitation;
        locale = Locale.forLanguageTag(context.getString(R.string.app_language_tag));
        this.decimals = decimals;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density;
        readingTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, metrics);
        dashed = new DashPathEffect(new float[]{4 * density, 3 * density}, 0);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double[] readings : precipitation ? new double[][]{first} : new double[][]{first, second}) {
            for (double value : readings) {
                if (!Double.isFinite(value)) continue;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        hasReadings = Double.isFinite(minimum) || precipitation;
        if (precipitation) {
            low = 0;
            high = Math.max(1, Math.ceil(maximum));
        } else if (wind) {
            low = 0;
            high = Double.isFinite(maximum) ? Math.max(2, Math.ceil(maximum * 1.08 / 2) * 2) : 2;
        } else if (humidity) {
            low = 0;
            high = 100;
        } else {
            double extent = hasReadings ? Math.max(2, Math.ceil(Math.max(-minimum, maximum) * 1.08)) : 2;
            if (minimum < 0 && maximum > 0) {
                low = -extent;
                high = extent;
            } else {
                double evenExtent = Math.ceil(extent / 2) * 2;
                low = minimum < 0 ? -evenExtent : 0;
                high = minimum < 0 ? 0 : evenExtent;
            }
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float top = gridY(height, 0);
        float bottom = gridY(height, 2);
        primaryGradient = bottom > top ? gradient(primary, top, bottom, 0x50, 0x0d) : null;
        secondaryGradient = bottom > top ? gradient(secondary, top, bottom, 0x42, 0x09) : null;
    }

    private static Shader gradient(int color, float top, float bottom, int topAlpha, int bottomAlpha) {
        int rgb = color & 0x00ffffff;
        return new LinearGradient(0, top, 0, bottom, rgb | (topAlpha << 24), rgb | (bottomAlpha << 24),
                Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(density);
        paint.setColor(grid);
        for (int i = 0; i <= 2; i++) {
            float y = gridY(getHeight(), i);
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
        if (!hasReadings) return;
        double secondaryLow = precipitation ? 0 : low;
        double secondaryHigh = precipitation ? 100 : high;
        drawArea(canvas, second, secondaryGradient, secondaryLow, secondaryHigh);
        if (precipitation) drawBars(canvas);
        else {
            drawArea(canvas, first, primaryGradient, low, high);
            drawLine(canvas, first, primary, null, low, high);
        }
        drawLine(canvas, second, secondary, dashed, secondaryLow, secondaryHigh);
        drawReadings(canvas);
    }

    View scale(boolean right) {
        View scale = new View(getContext()) {
            private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                if (!hasReadings) return;
                labels.setColor(muted);
                labels.setTextSize(readingTextSize);
                labels.setTextAlign(right ? Paint.Align.LEFT : Paint.Align.RIGHT);
                for (int i = 0; i <= 2; i++) {
                    double value = right ? 100 - 50 * i : high - (high - low) * i / 2;
                    String format = precipitation && !right ? "%.1f" : "%.0f";
                    canvas.drawText(String.format(locale, format, value), right ? 4 * density : getWidth() - 4 * density,
                            gridY(getHeight(), i) - (labels.ascent() + labels.descent()) / 2, labels);
                }
            }
        };
        scale.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return scale;
    }

    private float gridY(int height, int tick) {
        float padding = Math.max(13 * density, readingTextSize + 5 * density);
        return padding + (height - 2 * padding) * tick / 2;
    }

    private void drawArea(Canvas canvas, double[] readings, Shader shading, double minimum, double maximum) {
        if (shading == null || readings.length == 0) return;
        float baseline = pointY(Math.max(minimum, Math.min(0, maximum)), minimum, maximum);
        path.reset();
        int start = -1;
        for (int i = 0; i <= readings.length; i++) {
            if (i < readings.length && Double.isFinite(readings[i])) {
                float x = (i + .5f) * hourWidth;
                float y = pointY(readings[i], minimum, maximum);
                if (start < 0) {
                    start = i;
                    path.moveTo(x, baseline);
                }
                path.lineTo(x, y);
            } else if (start >= 0) {
                path.lineTo((i - .5f) * hourWidth, baseline);
                path.close();
                start = -1;
            }
        }
        paint.setColor(primary);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shading);
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private void drawBars(Canvas canvas) {
        paint.setColor(primary);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < first.length; i++) {
            if (!Double.isFinite(first[i]) || first[i] <= 0) continue;
            float center = (i + .5f) * hourWidth;
            float halfWidth = hourWidth * .24f;
            float top = pointY(first[i], low, high);
            float bottom = gridY(getHeight(), 2);
            if (bottom <= top) continue;
            paint.setShader(gradient(primary, top, bottom, 0xff, 0x80));
            canvas.drawRoundRect(center - halfWidth, top, center + halfWidth, bottom, 2 * density, 2 * density, paint);
        }
        paint.setShader(null);
    }

    private void drawLine(Canvas canvas, double[] values, int color, DashPathEffect style, double minimum, double maximum) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setPathEffect(style);
        path.reset();
        boolean connected = false;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                connected = false;
                continue;
            }
            float x = (i + .5f) * hourWidth;
            float y = pointY(values[i], minimum, maximum);
            if (connected) path.lineTo(x, y);
            else path.moveTo(x, y);
            connected = true;
        }
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i])) canvas.drawCircle((i + .5f) * hourWidth, pointY(values[i], minimum, maximum), 2 * density, paint);
        }
    }

    private void drawReadings(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(readingTextSize);
        for (int i = 0; i < first.length; i++) {
            float x = (i + .5f) * hourWidth;
            double a = first[i];
            double b = i < second.length ? second[i] : Double.NaN;
            float aY = pointY(a, low, high);
            float bY = pointY(b, precipitation ? 0 : low, precipitation ? 100 : high);
            boolean firstAbove = !precipitation && (!Double.isFinite(b) || aY <= bY);
            drawReading(canvas, a, x, precipitation ? gridY(getHeight(), 2) : aY, firstAbove, primary, decimals);
            drawReading(canvas, b, x, bY, !Double.isFinite(a) || !firstAbove,
                    secondary, precipitation ? 0 : decimals);
        }
    }

    private void drawReading(Canvas canvas, double value, float x, float y, boolean above, int color, int precision) {
        if (!Double.isFinite(value)) return;
        paint.setColor(color);
        String format = precision == 0 ? "%.0f" : "%.1f";
        canvas.drawText(String.format(locale, format, value), x,
                y + (above ? -4 * density : -paint.ascent() + 4 * density), paint);
    }

    private float pointY(double value, double minimum, double maximum) {
        float top = gridY(getHeight(), 0);
        return top + (gridY(getHeight(), 2) - top) * (float) ((maximum - value) / (maximum - minimum));
    }
}
