package it.skynow.app.astronomy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class EclipseIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path disk = new Path();
    private final EclipseCatalog.Event event;

    public EclipseIcon(Context context, EclipseCatalog.Event event) {
        super(context);
        this.event = event;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.min(centerX, centerY) * .73f;
        if (radius <= 0) return;
        paint.setColor(event.lunar ? 0xffd9e3f8 : 0xffffeaca);
        canvas.drawCircle(centerX, centerY, radius * 1.25f, paint);
        paint.setColor(event.lunar ? 0xffecdfd7 : 0xffe9a946);
        canvas.drawCircle(centerX, centerY, radius, paint);

        disk.reset();
        disk.addCircle(centerX, centerY, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(disk);
        paint.setColor(event.lunar ? event.penumbral ? 0xffa18d88 : 0xffa85539 : 0xff293a58);
        if (Double.isFinite(event.shadowX) && Double.isFinite(event.shadowY) && Double.isFinite(event.shadowRadius)) {
            canvas.drawCircle(centerX + (float) event.shadowX * radius, centerY - (float) event.shadowY * radius,
                    (float) event.shadowRadius * radius, paint);
        }
        canvas.restore();
    }
}
