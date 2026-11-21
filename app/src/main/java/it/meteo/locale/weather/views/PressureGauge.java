package it.meteo.locale.weather.views;

import it.meteo.locale.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.function.DoubleFunction;

public final class PressureGauge extends View {
    private static final int MIN_HPA = 950;
    private static final int MAX_HPA = 1050;
    private static final float START_ANGLE = 135;
    private static final float SWEEP_ANGLE = 270;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final double pressure;
    private final double minimum;
    private final double maximum;
    private final String minimumLabel;
    private final String maximumLabel;

    public PressureGauge(Context context, double pressure, DoubleFunction<String> format) {
        super(context);
        this.pressure = pressure;
        double center = (MIN_HPA + MAX_HPA) / 2.0;
        if (Double.isFinite(pressure) && (pressure < MIN_HPA || pressure > MAX_HPA)) {
            center = Math.round(pressure / 50) * 50.0;
        }
        double halfRange = (MAX_HPA - MIN_HPA) / 2.0;
        minimum = center - halfRange;
        maximum = center + halfRange;
        minimumLabel = format.apply(minimum);
        maximumLabel = format.apply(maximum);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float centerX = getWidth() / 2f;
        float radius = Math.min(getWidth() * 0.37f, getHeight() * 0.33f);
        float centerY = radius + 4 * density;
        arc.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(getContext().getColor(R.color.sky_pressure_track));
        canvas.drawArc(arc, START_ANGLE, SWEEP_ANGLE, false, paint);
        if (Double.isFinite(pressure)) {
            double fraction = (pressure - minimum) / (maximum - minimum);
            float sweep = SWEEP_ANGLE * (float) Math.max(0, Math.min(1, fraction));
            paint.setColor(getContext().getColor(R.color.sky_pressure_value));
            canvas.drawArc(arc, START_ANGLE, sweep, false, paint);
            double angle = Math.toRadians(START_ANGLE + sweep);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getContext().getColor(R.color.sky_pressure_marker));
            canvas.drawCircle(centerX + radius * (float) Math.cos(angle),
                    centerY + radius * (float) Math.sin(angle), 2.6f * density, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(10.5f * density);
        paint.setColor(getContext().getColor(R.color.sky_gauge_label));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(minimumLabel, 0, getHeight() - 2 * density, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(maximumLabel, getWidth(), getHeight() - 2 * density, paint);
    }
}
