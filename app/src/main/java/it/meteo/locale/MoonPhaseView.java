package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class MoonPhaseView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lit = new Path();
    private final RectF disk = new RectF();
    private final double phase;
    private final boolean southern;

    MoonPhaseView(Context context, double phase, boolean southern) {
        super(context);
        this.phase = phase;
        this.southern = southern;
    }

    static double illumination(double phase) {
        return (1 - Math.cos(2 * Math.PI * phase)) / 2;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        float radius = Math.max(0, Math.min(x, y) - 1);
        if (radius == 0) return;

        paint.setColor(0xff27354e);
        canvas.drawCircle(x, y, radius, paint);
        double terminator = Math.cos(2 * Math.PI * phase);
        float side = (phase < .5) != southern ? 1 : -1;
        disk.set(x - radius, y - radius, x + radius, y + radius);
        lit.reset();
        lit.arcTo(disk, -90, side * 180, true);
        for (int i = 1; i <= 48; i++) {
            double t = 1 - i / 24.0;
            float dx = (float) (side * terminator * radius * Math.sqrt(Math.max(0, 1 - t * t)));
            lit.lineTo(x + dx, y + (float) t * radius);
        }
        lit.close();
        paint.setColor(0xffffe6a5);
        canvas.drawPath(lit, paint);
    }
}
