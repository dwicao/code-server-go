package dev.codeservergo;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Full-screen overlay used during connect / on error, instead of a bare WebView. */
final class EditorOverlay {

    private final View overlay;
    private final View spinner;
    private final TextView overlayTitle;
    private final TextView overlayBody;
    private final Button button;

    EditorOverlay(View overlay) {
        this.overlay = overlay;
        this.spinner = overlay.findViewById(R.id.overlay_spinner);
        this.overlayTitle = overlay.findViewById(R.id.overlay_title);
        this.overlayBody = overlay.findViewById(R.id.overlay_body);
        this.button = (Button) overlay.findViewById(R.id.overlay_button);
    }

    /** Applies theme colours to the overlay + its text. */
    void applyPalette(Palette pal) {
        overlay.setBackgroundColor(pal.bg);
        overlayTitle.setTextColor(pal.text);
        overlayBody.setTextColor(pal.textDim);
    }

    /** Connecting / loading state, with the spinner on and the retry button hidden. */
    void showLoading(String label) {
        overlayTitle.setText(label);
        overlayBody.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        button.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
    }

    /** Human-readable failure text; actionable retry; keeps the page title visible. */
    void showError(String detail, String untranslatedLabel) {
        overlayTitle.setText(untranslatedLabel);
        overlayBody.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        button.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
    }

    void setErrorBody(String text) {
        overlayBody.setText(text);
    }

    void setRetry(String label, Runnable onRetry) {
        button.setText(label);
        button.setOnClickListener(v -> onRetry.run());
    }

    boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    void hide() {
        overlay.setVisibility(View.GONE);
    }
}
