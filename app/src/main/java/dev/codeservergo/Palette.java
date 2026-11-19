package dev.codeservergo;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Colour palette for both themes.
 *
 * <p>Keeping colours in code (instead of {@code values-night} resources) means the theme can be
 * flipped at runtime without recreating every view hierarchy of the live editor WebView — which
 * would drop the VS Code session and any unsaved buffer.
 */
final class Palette {

    final boolean dark;
    final int bg;
    final int surface;
    final int surfaceAlt;
    final int text;
    final int textDim;
    final int accent;
    final int divider;
    final int ok;
    final int warn;
    final int danger;
    final int dotIdle;

    private Palette(boolean dark, int bg, int surface, int surfaceAlt, int text, int textDim,
                    int accent, int divider, int ok, int warn, int danger, int dotIdle) {
        this.dark = dark;
        this.bg = bg;
        this.surface = surface;
        this.surfaceAlt = surfaceAlt;
        this.text = text;
        this.textDim = textDim;
        this.accent = accent;
        this.divider = divider;
        this.ok = ok;
        this.warn = warn;
        this.danger = danger;
        this.dotIdle = dotIdle;
    }

    static final Palette DARK = new Palette(
            true,
            0xFF14171C, // bg
            0xFF1A1E24, // surface (bars)
            0xFF232830, // surfaceAlt (pills, chips)
            0xFFE6E9EF, // text
            0xFF7F8895, // textDim
            0xFF3E8EF7, // accent
            0x1AFFFFFF, // divider
            0xFF33C27F, // ok
            0xFFE0A93B, // warn
            0xFFE5534B, // danger
            0xFF4A5260  // dotIdle
    );

    static final Palette LIGHT = new Palette(
            false,
            0xFFF4F5F7,
            0xFFFFFFFF,
            0xFFE9ECF1,
            0xFF1B1F26,
            0xFF6B7280,
            0xFF1E6FD9,
            0x1A000000,
            0xFF1F9D63,
            0xFFB4791B,
            0xFFD93B33,
            0xFFB8C0CC
    );

    static Palette forContext(Context c, Prefs prefs) {
        switch (prefs.theme()) {
            case Prefs.THEME_LIGHT:
                return LIGHT;
            case Prefs.THEME_DARK:
                return DARK;
            default:
                int mode = c.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                return mode == Configuration.UI_MODE_NIGHT_NO ? LIGHT : DARK;
        }
    }
}
