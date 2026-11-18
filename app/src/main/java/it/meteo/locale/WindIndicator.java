package it.meteo.locale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class WindIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointer = new Path();
    private final double degrees;

    WindIndicator(Context context, double degrees) {
        super(context);
        this.degrees = degrees;
        pointer.moveTo(0, -0.69f);
        pointer.lineTo(0.40f, 0.40f);
        pointer.lineTo(0, 0.22f);
        pointer.lineTo(-0.40f, 0.40f);
        pointer.close();
        setContentDescription(Double.isFinite(degrees) ? "Wind from " + Math.round(degrees) + " degrees" : "Wind direction unavailable");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Math.min(getWidth(), getHeight()) * 0.46f;
        if (radius <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(radius, radius);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffe5f2ed);
        canvas.drawCircle(0, 0, 0.89f, paint);
        paint.setColor(0xffa2b8ae);
        paint.setStrokeWidth(0.04f);
        for (int i = 0; i < 4; i++) {
            canvas.drawLine(0, -0.82f, 0, -0.70f, paint);
            canvas.rotate(90);
        }
        paint.setColor(0xff304f46);
        canvas.drawCircle(0, -0.93f, 0.07f, paint);
        if (Double.isFinite(degrees)) {
            canvas.rotate((float) (degrees + 180));
            paint.setColor(0xff007768);
            canvas.drawPath(pointer, paint);
        }
        canvas.restore();
    }
}
