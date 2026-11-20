package com.dafeiyu.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class Ui {
    private Ui() { }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int sp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity);
    }

    public static int color(Context c, int res) { return c.getColor(res); }

    public static Typeface regular() { return Typeface.create("sans-serif", Typeface.NORMAL); }
    public static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }
    public static Typeface bold() { return Typeface.create("sans-serif", Typeface.BOLD); }
    public static Typeface mono() { return Typeface.create("monospace", Typeface.NORMAL); }

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(bold ? medium() : regular());
        t.setLineSpacing(dp(c, 3), 1f);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dpf(c, radiusDp));
        return d;
    }

    public static GradientDrawable rounded(Context c, int fill, float[] radiiDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        float[] r = new float[8];
        for (int i = 0; i < 4; i++) {
            r[i * 2] = dpf(c, radiiDp[i]);
            r[i * 2 + 1] = dpf(c, radiiDp[i]);
        }
        d.setCornerRadii(r);
        return d;
    }

    public static GradientDrawable stroke(Context c, int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    public static RippleDrawable ripple(GradientDrawable bg, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null);
    }

    public static ImageView icon(Context c, int res, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return iv;
    }

    /** 44dp tappable circular icon button. */
    public static FrameLayout iconButton(Context c, int res, int tint, int bgColor, int rippleColor) {
        FrameLayout holder = new FrameLayout(c);
        int size = dp(c, 42);
        holder.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        holder.setBackground(ripple(rounded(c, bgColor, 21), rippleColor));
        ImageView iv = icon(c, res, tint);
        int pad = dp(c, 10);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(pad, pad, pad, pad);
        holder.addView(iv, lp);
        holder.setClickable(true);
        holder.setFocusable(true);
        return holder;
    }

    public static void margins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(l, t, r, b);
            v.setLayoutParams(lp);
        }
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static View hspace(Context c, int w) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), 1));
        return v;
    }

    public static String time(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms));
    }

    public static String dayLabel(long ms) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        if (sameDay(now, then)) return "今天";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, then)) return "昨天";
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(ms));
    }

    public static String relative(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000) return "刚刚";
        if (diff < 3600_000) return (diff / 60_000) + " 分钟前";
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        if (sameDay(now, then)) return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms));
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, then)) return "昨天";
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(ms));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    public static void copy(Context c, String label, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
    }

    public static void openUrl(Context c, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "无法打开链接");
        }
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static int blend(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a), aa = Color.alpha(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b), ba = Color.alpha(b);
        return Color.argb(
                (int) (aa + (ba - aa) * t),
                (int) (ar + (br - ar) * t),
                (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }

    public static void press(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                case android.view.MotionEvent.ACTION_UP:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
            }
            return false;
        });
    }
}
