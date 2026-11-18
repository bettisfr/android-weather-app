package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;

final class HourlyLineChart extends View {
    static final int PRIMARY = 0xff435e94;
    static final int SECONDARY = 0xffa55d23;
    private static final int GRID = 0xffdce3ed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DashPathEffect dashed;
    private final float density;
    private final float readingTextSize;
    private final float scaleTextSize;
    private final double[] first;
    private final double[] second;
    private final int hourWidth;
    private final double low;
    private final double high;
    private final boolean hasReadings;
    private final boolean precipitation;
    private final Locale locale;
    private final int decimals;

    HourlyLineChart(Context context, double[] first, double[] second, int hourWidth, boolean precipitation, boolean humidity, int decimals) {
        super(context);
        this.first = first;
        this.second = second;
        this.hourWidth = hourWidth;
        this.precipitation = precipitation;
        locale = Locale.forLanguageTag(context.getString(R.string.app_language_tag));
        this.decimals = decimals;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density;
        readingTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9, metrics);
        scaleTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f, metrics);
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
        } else if (humidity) {
            low = 0;
            high = 100;
        } else {
            double step = Math.max(1, Math.ceil((maximum - minimum) / 2));
            double upper = Math.floor(minimum / step) * step + step * 2;
            high = Math.max(maximum, upper);
            low = high - step * 2;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(density);
        paint.setColor(GRID);
        for (int i = 0; i <= 2; i++) {
            float y = gridY(getHeight(), i);
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
        if (!hasReadings) return;
        if (precipitation) drawBars(canvas);
        else drawLine(canvas, first, PRIMARY, null, low, high);
        drawLine(canvas, second, SECONDARY, dashed, precipitation ? 0 : low, precipitation ? 100 : high);
        drawReadings(canvas);
    }

    View scale(boolean right) {
        View scale = new View(getContext()) {
            private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                if (!hasReadings) return;
                labels.setColor(0xff546074);
                labels.setTextSize(scaleTextSize);
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

    private void drawBars(Canvas canvas) {
        paint.setColor(PRIMARY);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < first.length; i++) {
            if (!Double.isFinite(first[i]) || first[i] <= 0) continue;
            float center = (i + .5f) * hourWidth;
            float halfWidth = hourWidth * .24f;
            canvas.drawRoundRect(center - halfWidth, pointY(first[i], low, high), center + halfWidth,
                    gridY(getHeight(), 2), 2 * density, 2 * density, paint);
        }
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
            drawReading(canvas, a, x, aY, firstAbove, PRIMARY, decimals);
            drawReading(canvas, b, x, bY, !Double.isFinite(a) || !firstAbove,
                    SECONDARY, precipitation ? 0 : decimals);
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
