package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class UvGauge extends View {
    private static final int[] COLORS = {0xff56a984, 0xffd5a43c, 0xffd9803d, 0xffbe595c, 0xff8266a4};
    private static final int[] LEVELS = {0, 3, 6, 8, 11};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double index;

    UvGauge(Context context, double index) {
        super(context);
        this.index = index;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float center = getWidth() / 2f;
        float bottom = getHeight() - 16 * density;
        float unit = (bottom - 15 * density) / 12;
        for (int i = 0; i < LEVELS.length; i++) {
            int end = i + 1 < LEVELS.length ? LEVELS[i + 1] : 12;
            paint.setColor(COLORS[i]);
            canvas.drawRoundRect(center - 7 * density, bottom - end * unit,
                    center + 7 * density, bottom - LEVELS[i] * unit - density,
                    2 * density, 2 * density, paint);
        }
        if (Double.isFinite(index)) {
            float y = bottom - (float) Math.min(12, Math.max(0, index)) * unit;
            paint.setColor(0xff163e38);
            canvas.drawCircle(center - 11 * density, y, 2.2f * density, paint);
        }
        paint.setColor(0xff526a63);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(11.5f * density);
        canvas.drawText("11+", center, 12 * density, paint);
        canvas.drawText("0", center, getHeight() - 2 * density, paint);
    }
}
