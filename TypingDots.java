package com.dafeiyu.chat;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class TypingDots extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] phase = {0f, 0.18f, 0.36f};
    private ValueAnimator anim;
    private float t;
    private int color = 0xFF9AA1AC;

    public TypingDots(Context c) { super(c); init(); }
    public TypingDots(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        p.setColor(color);
        setAlpha(0.9f);
    }

    public void setDotColor(int c) { color = c; p.setColor(c); invalidate(); }

    public void start() {
        if (anim != null) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1100);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) * 0.09f;
        float gap = r * 2.5f;
        float cx = w / 2f - gap;
        float cy = h / 2f;
        for (int i = 0; i < 3; i++) {
            float ph = (t + phase[i]) % 1f;
            float lift = (float) Math.sin(ph * Math.PI * 2) * r * 1.2f;
            int alpha = (int) (110 + 100 * (0.5f + 0.5f * Math.sin(ph * Math.PI * 2)));
            p.setColor(color);
            p.setAlpha(Math.max(40, Math.min(255, alpha)));
            canvas.drawCircle(cx + i * gap, cy - Math.max(0, lift), r, p);
        }
    }
}
