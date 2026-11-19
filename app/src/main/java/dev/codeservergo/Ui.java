package dev.codeservergo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/** Tiny view helpers. No AndroidX: everything here is framework API. */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void toast(Context c, String msg) {
        if (c == null || msg == null) return;
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    static void tint(ImageView v, int color) {
        v.setColorFilter(color);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(Context c, int color, int strokeColor, float radiusDp) {
        GradientDrawable d = round(c, color, radiusDp);
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    static GradientDrawable dot(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static RippleDrawable ripple(Context c, int color, float radiusDp) {
        GradientDrawable mask = round(c, 0xFFFFFFFF, radiusDp);
        ColorStateList csl = ColorStateList.valueOf(alpha(color, 0.18f));
        return new RippleDrawable(csl, null, mask);
    }

    static TextView text(Context c, String s, int color, float sizeSp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static View spacer(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, dp(c, h)));
        return v;
    }

    static View hLine(Context c, int color) {
        View v = new View(c);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(c, 1)));
        v.setLayoutParams(lp);
        v.setBackgroundColor(color);
        return v;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int s = dp(c, sizeDp);
        iv.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        return iv;
    }

    static TextView chip(Context c, String label, Palette pal) {
        TextView t = text(c, label, pal.text, 12f, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundStroke(c, pal.surfaceAlt, alpha(0xFFFFFFFF, pal.dark ? 0.06f : 0.05f), 9));
        int ph = dp(c, 10), pv = dp(c, 5);
        t.setPadding(ph, pv, ph, pv);
        return t;
    }
}
