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
        setContentDescription(Double.isFinite(degrees) ? context.getString(R.string.wind_from_degrees, Math.round(degrees))
                : context.getString(R.string.wind_direction_unavailable));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Math.min(getWidth(), getHeight()) * 0.46f;
        if (radius <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(radius, radius);
        if (Double.isFinite(degrees)) {
            canvas.rotate((float) (degrees + 180));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getContext().getColor(R.color.sky_accent));
            canvas.drawPath(pointer, paint);
        }
        canvas.restore();
    }
}
