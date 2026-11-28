package it.skynow.app.weather.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import it.skynow.app.R;
import it.skynow.app.weather.AirQualityBand;

/** European AQI scale drawn like {@link UvGauge}: one coloured step per band, with a marker at the index. */
public final class AqiGauge extends View {
    /** The scale runs to 120 so the extreme band has the same height as the others. */
    private static final double TOP = 120;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double index;

    public AqiGauge(Context context, double index) {
        super(context);
        this.index = index;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Moves the marker, for an index that arrives after the view is shown. */
    public void setIndex(double index) {
        this.index = index;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float center = getWidth() / 2f;
        float bottom = getHeight() - 16 * density;
        float unit = (float) ((bottom - 15 * density) / TOP);
        double lower = 0;
        for (AirQualityBand band : AirQualityBand.values()) {
            double upper = Math.min(band.upper, TOP);
            paint.setColor(band.color);
            canvas.drawRoundRect(center - 7 * density, bottom - (float) upper * unit,
                    center + 7 * density, bottom - (float) lower * unit - density,
                    2 * density, 2 * density, paint);
            lower = upper;
        }
        if (Double.isFinite(index)) {
            float y = bottom - (float) Math.min(TOP, Math.max(0, index)) * unit;
            paint.setColor(getContext().getColor(R.color.sky_uv_marker));
            canvas.drawCircle(center - 11 * density, y, 2.2f * density, paint);
        }
        paint.setColor(getContext().getColor(R.color.sky_gauge_label));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(11.5f * density);
        canvas.drawText("100+", center, 12 * density, paint);
        canvas.drawText("0", center, getHeight() - 2 * density, paint);
    }
}
