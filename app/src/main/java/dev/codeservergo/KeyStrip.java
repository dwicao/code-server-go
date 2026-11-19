package dev.codeservergo;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The "extra key row": the keys a phone keyboard physically cannot send.
 *
 * <p>An on-screen keyboard has no Escape, no Tab, no arrows and no Ctrl, which makes a browser IDE
 * quite painful on a phone. This strip dispatches real {@link KeyEvent}s into the WebView, so VS
 * Code's keybindings (Ctrl+S, Ctrl+P, Ctrl+Shift+P, Ctrl+`) work exactly as on a desktop.
 */
final class KeyStrip {

    private KeyStrip() {
    }

    private static final class Key {
        final String label;
        final int keyCode;
        final int meta;
        final float widthDp;

        Key(String label, int keyCode, int meta, float widthDp) {
            this.label = label;
            this.keyCode = keyCode;
            this.meta = meta;
            this.widthDp = widthDp;
        }
    }

    private static final int CTRL = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
    private static final int CTRL_SHIFT = CTRL | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;

    static void populate(LinearLayout strip, final WebView web, final Palette pal) {
        strip.removeAllViews();

        HorizontalScrollView scroll = new HorizontalScrollView(strip.getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(strip.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(strip.getContext(), 2), 0, Ui.dp(strip.getContext(), 2), 0);

        List<Key> keys = new ArrayList<>();
        keys.add(new Key("Esc", KeyEvent.KEYCODE_ESCAPE, 0, 46));
        keys.add(new Key("Tab", KeyEvent.KEYCODE_TAB, 0, 46));
        keys.add(new Key("\u2190", KeyEvent.KEYCODE_DPAD_LEFT, 0, 40));
        keys.add(new Key("\u2191", KeyEvent.KEYCODE_DPAD_UP, 0, 40));
        keys.add(new Key("\u2193", KeyEvent.KEYCODE_DPAD_DOWN, 0, 40));
        keys.add(new Key("\u2192", KeyEvent.KEYCODE_DPAD_RIGHT, 0, 40));
        keys.add(new Key("Ctrl+S", KeyEvent.KEYCODE_S, CTRL, 58));
        keys.add(new Key("Ctrl+P", KeyEvent.KEYCODE_P, CTRL, 58));
        keys.add(new Key("Ctrl+\u21e7P", KeyEvent.KEYCODE_P, CTRL_SHIFT, 66));
        keys.add(new Key("Ctrl+`", KeyEvent.KEYCODE_GRAVE, CTRL, 58));
        keys.add(new Key("Ctrl+B", KeyEvent.KEYCODE_B, CTRL, 58));
        keys.add(new Key("Ctrl+F", KeyEvent.KEYCODE_F, CTRL, 58));
        keys.add(new Key("Ctrl+/", KeyEvent.KEYCODE_SLASH, CTRL, 58));

        for (final Key k : keys) {
            TextView chip = new TextView(strip.getContext());
            chip.setText(k.label);
            chip.setTextColor(pal.text);
            chip.setTextSize(12f);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_key);
            chip.setClickable(true);
            chip.setFocusable(false);
            int h = Ui.dp(strip.getContext(), 30);
            int w = Ui.dp(strip.getContext(), k.widthDp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = Ui.dp(strip.getContext(), 5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    send(web, k.keyCode, k.meta);
                }
            });
            row.addView(chip);
        }

        // A few conveniences that are not chords.
        TextView save = new TextView(strip.getContext());
        save.setText("\u2328");
        save.setTextColor(pal.text);
        save.setTextSize(14f);
        save.setGravity(Gravity.CENTER);
        save.setBackgroundResource(R.drawable.bg_key);
        save.setClickable(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(strip.getContext(), 40), Ui.dp(strip.getContext(), 30));
        slp.rightMargin = Ui.dp(strip.getContext(), 5);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) v.getContext()
                                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.toggleSoftInput(
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0);
            }
        });
        row.addView(save);

        scroll.addView(row);
        strip.addView(scroll);
    }

    /** Ships a synthetic key press straight into the page. */
    static void send(WebView web, int keyCode, int meta) {
        if (web == null) return;
        web.requestFocus();
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta);
        web.dispatchKeyEvent(down);
        web.dispatchKeyEvent(up);
    }
}
