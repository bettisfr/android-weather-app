package it.meteo.locale.astronomy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public final class PlanetIcon extends View {
    private final Planets.Body body;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path disc = new Path();

    public PlanetIcon(Context context, Planets.Body body) {
        super(context);
        this.body = body;
        disc.addCircle(16, 16, body == Planets.Body.SATURN ? 9 : 13, Path.Direction.CW);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int saved = canvas.save();
        canvas.scale(getWidth() / 32f, getHeight() / 32f);
        switch (body) {
            case MERCURY:
                sphere(canvas, 0xffaab2bb);
                oval(canvas, 6, 9, 14, 16, 0xff818d9b);
                oval(canvas, 17, 16, 25, 24, 0xff8d99a6);
                oval(canvas, 11, 20, 15, 24, 0xff737f8d);
                oval(canvas, 17, 8, 21, 12, 0xffc7cdd3);
                break;
            case VENUS:
                sphere(canvas, 0xffe2b969);
                oval(canvas, 0, 6, 34, 15, 0xffffe5a6);
                oval(canvas, -2, 14, 34, 21, 0xfff6d38f);
                oval(canvas, 6, 22, 34, 28, 0xffffe3aa);
                break;
            case MARS:
                sphere(canvas, 0xffd57253);
                oval(canvas, 5, 10, 20, 17, 0xffe89b6d);
                oval(canvas, 15, 17, 30, 25, 0xffa64b3d);
                oval(canvas, 7, 23, 15, 27, 0xffbc5845);
                oval(canvas, 11, 2, 21, 8, 0xffffd4b4);
                break;
            case JUPITER:
                sphere(canvas, 0xffedc9a1);
                oval(canvas, -3, 8, 35, 13, 0xffb97f59);
                oval(canvas, -3, 15, 35, 20, 0xfff8dfbd);
                oval(canvas, -3, 21, 35, 27, 0xffbd8b66);
                oval(canvas, 17, 20, 26, 25, 0xffbc5d4c);
                break;
            case SATURN:
                ring(canvas, 0xffcba879, 3.5f, 180, 180);
                canvas.save();
                sphere(canvas, 0xffe7bf77);
                oval(canvas, 5, 10, 28, 15, 0xfff5d89b);
                oval(canvas, 5, 17, 28, 21, 0xffc69b60);
                canvas.restore();
                ring(canvas, 0xffeac996, 3.5f, 0, 180);
                ring(canvas, 0xffb3895e, .8f, 0, 180);
                break;
        }
        canvas.restoreToCount(saved);
    }

    private void sphere(Canvas canvas, int color) {
        canvas.clipPath(disc);
        canvas.drawColor(color);
    }

    private void oval(Canvas canvas, float left, float top, float right, float bottom, int color) {
        paint.setColor(color);
        canvas.drawOval(left, top, right, bottom, paint);
    }

    private void ring(Canvas canvas, int color, float width, int start, int sweep) {
        int saved = canvas.save();
        canvas.rotate(-24, 16, 16);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        canvas.drawArc(1.8f, 10.4f, 30.2f, 21.6f, start, sweep, false, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(saved);
    }
}
