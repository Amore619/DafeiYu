package com.dafeiyu.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** The chubby fish mark, drawn small enough to feel at home in a chat header. */
public class LogoView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path tail = new Path();
    private final Path fin = new Path();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean badge = true;
    private int brand = 0xFF4D6BFE;

    public LogoView(Context context) {
        super(context);
        init();
    }

    public LogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LogoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setBadge(boolean badge) { this.badge = badge; invalidate(); }
    public void setBrand(int color) { this.brand = color; invalidate(); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int s = Math.min(w, h);
        if (s == 0) s = Ui.dp(getContext(), 40);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        if (badge) {
            paint.setShader(new LinearGradient(0, 0, s, s,
                    new int[]{brand, Ui.blend(brand, Color.WHITE, 0.22f)},
                    null, Shader.TileMode.CLAMP));
            RectF r = new RectF(0, 0, s, s);
            canvas.drawRoundRect(r, s * 0.30f, s * 0.30f, paint);
            paint.setShader(null);
            paint.setColor(0x22FFFFFF);
            canvas.drawCircle(s * 0.72f, s * 0.22f, s * 0.20f, paint);
        }

        float pad = badge ? s * 0.19f : s * 0.02f;
        float k = (s - pad * 2) / 100f;
        canvas.save();
        canvas.translate(pad, pad);
        canvas.scale(k, k);

        int fishColor = badge ? Color.WHITE : brand;
        paint.setColor(fishColor);
        paint.setStyle(Paint.Style.FILL);
        body.reset();
        body.moveTo(38, 50);
        body.cubicTo(38, 38, 52, 32, 66, 35);
        body.cubicTo(82, 39, 88, 52, 81, 63);
        body.cubicTo(73, 75, 52, 75, 44, 64);
        body.cubicTo(40, 59, 38, 55, 38, 50);
        body.close();
        canvas.drawPath(body, paint);

        tail.reset();
        tail.moveTo(40, 52);
        tail.cubicTo(33, 47, 27, 40, 23, 32);
        tail.cubicTo(18, 45, 18, 56, 21, 68);
        tail.cubicTo(26, 62, 32, 57, 40, 55);
        tail.close();
        canvas.drawPath(tail, paint);

        fin.reset();
        fin.moveTo(57, 65);
        fin.cubicTo(60, 72, 66, 76, 73, 76);
        fin.cubicTo(70, 71, 68, 67, 68, 63);
        fin.close();
        canvas.drawPath(fin, paint);

        // Eye and smile are the brand colour on the white fish.
        paint.setColor(badge ? brand : Color.WHITE);
        canvas.drawCircle(68, 45, 3.1f, paint);
        paint.setColor(badge ? 0xAA4D6BFE : 0xCCFFFFFF);
        canvas.drawCircle(68, 45, 1.25f, paint);

        stroke.setColor(badge ? 0xAA4D6BFE : 0xCCFFFFFF);
        stroke.setStrokeWidth(2.2f);
        RectF smile = new RectF(58, 51, 70, 61);
        canvas.drawArc(smile, 15, 80, false, stroke);

        paint.setColor(badge ? 0xCCFFFFFF : 0x884D6BFE);
        canvas.drawCircle(88, 29, 2.3f, paint);
        canvas.drawCircle(93, 21, 1.4f, paint);
        canvas.restore();
    }
}
