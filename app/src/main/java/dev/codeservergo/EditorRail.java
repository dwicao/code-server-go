package dev.codeservergo;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** DeX / tablet multi-server rail. */
final class EditorRail {

    interface OnSelection {
        void onSelect(Server s);
    }

    interface OnInfo {
        void onInfo(Server s);
    }

    static String initial(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    static void populate(LinearLayout rail, List<Server> servers, String activeId,
                         Palette pal, OnSelection onSelect, OnInfo onInfo,
                         OnHomeCallback homeCallback) {
        rail.removeAllViews();
        for (final Server s : servers) {
            boolean active = s.id.equals(activeId);
            TextView t = Ui.text(rail.getContext(), initial(s.name),
                    active ? pal.accent : pal.textDim, 14f, true);
            t.setGravity(Gravity.CENTER);
            int size = Ui.dp(rail.getContext(), 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.topMargin = Ui.dp(rail.getContext(), 6);
            t.setLayoutParams(lp);
            Drawable bg = active ? rail.getContext().getDrawable(R.drawable.bg_rail_active)
                    : Ui.ripple(rail.getContext(), pal.accent, 12);
            t.setBackground(bg);
            t.setOnClickListener(v -> onSelect.onSelect(s));
            t.setOnLongClickListener(v -> {
                if (onInfo != null) onInfo.onInfo(s);
                return true;
            });
            rail.addView(t);
        }

        TextView add = Ui.text(rail.getContext(), "+", pal.textDim, 18f, false);
        add.setGravity(Gravity.CENTER);
        int size = Ui.dp(rail.getContext(), 40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = Ui.dp(rail.getContext(), 10);
        add.setLayoutParams(lp);
        add.setBackground(Ui.ripple(rail.getContext(), pal.accent, 12));
        add.setOnClickListener(v -> {
            if (homeCallback != null) homeCallback.onOpen();
        });
        rail.addView(add);
    }

    interface OnHomeCallback {
        void onOpen();
    }

    /** Convenience when only the active server + list matter. */
    static void populate(LinearLayout rail, List<Server> servers, String activeId,
                         Palette pal, OnSelection onSelect, OnHomeCallback homeCallback) {
        populate(rail, servers, activeId, pal, onSelect, null, homeCallback);
    }
}
