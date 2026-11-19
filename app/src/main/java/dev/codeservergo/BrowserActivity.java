package dev.codeservergo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;

/**
 * The editor shell.
 *
 * <p>Everything here exists because a browser tab is a bad host for a coding session on a phone or
 * in Samsung DeX: window resizes must not reload the page (configChanges + a live WebView),
 * a hardware keyboard needs Alt+Left navigation and IME toggles, a self-signed LAN certificate must
 * be pinnable, and the phone needs Traversal/Escape keys the on-screen keyboard cannot produce.
 */
public class BrowserActivity extends Activity {

    static final String EXTRA_SERVER_ID = "server_id";
    private static final int REQ_PICK_FILES = 0x51;
    private static final String STATE_URL = "live_url";

    private Prefs prefs;
    private ServerStore store;
    private Palette pal;
    private Server server;

    private WebView web;
    private LinearLayout topBar;
    private LinearLayout rail;
    private LinearLayout keyStrip;
    private FrameLayout root;
    private View overlayView;
    private ProgressBar progress;
    private TextView txtTitle;
    private TextView txtStatus;
    private EditorOverlay overlay;
    private View statusDot;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;
    private ImageButton btnKeyboard;
    private ImageButton btnFullscreen;
    private ImageView exitFab;
    private View fullscreenHint;

    private ValueCallback<Uri[]> fileCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean fullscreen;
    private boolean firstLoadDone;
    private long lastBackPress;
    private float zoom = 1f;
    private String pendingUrl;
    private boolean uiReady;

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle state) {
        prefs = new Prefs(this);
        store = new ServerStore(this);
        pal = Palette.forContext(this, prefs);
        setTheme(pal.dark ? R.style.Theme_CodeServerGo : R.style.Theme_CodeServerGo_Light);
        super.onCreate(state);

        server = resolveServer(getIntent());
        if (server == null) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        prefs.setLastServerId(server.id);
        store.touch(server.id);

        try {
            buildEditor(state);
            uiReady = true;
        } catch (Throwable t) {
            CrashLog.saveNow(this, t);
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            TextView view = Ui.text(this, "The editor shell could not start\n\n" + sw,
                    0xFFFF9E9E, 11f, false);
            view.setTypeface(android.graphics.Typeface.MONOSPACE);
            view.setTextIsSelectable(true);
            int pad = Ui.dp(this, 16);
            view.setPadding(pad, pad, pad, pad);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setBackgroundColor(0xFF14171C);
            scroll.addView(view);
            setContentView(scroll);
        }
    }

    private void buildEditor(Bundle state) {
        setContentView(R.layout.activity_browser);
        bindViews();
        applyPalette();
        zoom = prefs.zoom(server.id);
        buildChrome();
        buildWebView();

        if (state != null) {
            pendingUrl = state.getString(STATE_URL);
        }
        if (pendingUrl == null) pendingUrl = server.url;

        showLoading(getString(R.string.connecting));
        updateRail();
        updateKeyStrip();
    }

    private Server resolveServer(Intent intent) {
        String id = intent != null ? intent.getStringExtra(EXTRA_SERVER_ID) : null;
        Server s = store.get(id);
        if (s == null) s = store.get(prefs.lastServerId());
        if (s == null && !store.all().isEmpty()) s = store.all().get(0);
        return s;
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        topBar = findViewById(R.id.top_bar);
        rail = findViewById(R.id.rail);
        keyStrip = findViewById(R.id.key_strip);
        overlayView = findViewById(R.id.overlay);
        overlay = new EditorOverlay(overlayView);
        progress = findViewById(R.id.progress);
        txtTitle = findViewById(R.id.txt_title);
        txtStatus = findViewById(R.id.txt_status);
        statusDot = findViewById(R.id.status_dot);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        btnKeyboard = findViewById(R.id.btn_keyboard);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
    }

    private void applyPalette() {
        Window w = getWindow();
        w.setBackgroundDrawableResource(pal.dark ? R.color.bg : R.color.bg_light);
        w.setStatusBarColor(pal.dark ? 0xFF1A1E24 : 0xFFFFFFFF);
        w.setNavigationBarColor(pal.dark ? 0xFF1A1E24 : 0xFFFFFFFF);
        root.setBackgroundColor(pal.bg);
        topBar.setBackgroundColor(pal.surface);
        rail.setBackgroundColor(pal.dark ? 0xFF171B21 : 0xFFEDEFF3);
        keyStrip.setBackgroundColor(pal.dark ? 0xFF171B21 : 0xFFEDEFF3);
        overlay.applyPalette(pal);

        txtTitle.setTextColor(pal.text);
        statusDot.setBackground(Ui.dot(this, pal.dotIdle));

        int icon = pal.dark ? 0xFFD7DCE4 : 0xFF39414D;
        Ui.tint(btnBack, icon);
        Ui.tint(btnForward, icon);
        Ui.tint(btnReload, icon);
        Ui.tint(btnKeyboard, icon);
        Ui.tint(btnFullscreen, icon);
        Ui.tint((ImageButton) findViewById(R.id.btn_more), icon);
        Ui.tint((ImageButton) findViewById(R.id.btn_switch), icon);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !fullscreen) {
            int flags = w.getDecorView().getSystemUiVisibility();
            if (pal.dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void buildChrome() {
        btnBack.setOnClickListener(v -> {
            if (web != null && web.canGoBack()) web.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (web != null && web.canGoForward()) web.goForward();
        });
        btnReload.setOnClickListener(v -> {
            if (web != null) {
                firstLoadDone = false;
                showLoading(getString(R.string.loading));
                web.reload();
            }
        });
        btnKeyboard.setOnClickListener(v -> toggleKeyboard());
        btnFullscreen.setOnClickListener(v -> setFullscreen(true));
        findViewById(R.id.btn_switch).setOnClickListener(this::showServerMenu);
        findViewById(R.id.btn_more).setOnClickListener(this::showOverflow);

        // Floating exit affordance used while the chrome is hidden in full screen.
        exitFab = new ImageView(this);
        exitFab.setImageResource(R.drawable.ic_fullscreen_exit);
        exitFab.setColorFilter(0xFFFFFFFF);
        exitFab.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int size = Ui.dp(this, 36);
        exitFab.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        exitFab.setBackground(Ui.round(this, 0xCC000000, 10));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size);
        flp.gravity = Gravity.TOP | Gravity.END;
        flp.topMargin = Ui.dp(this, 10);
        flp.rightMargin = Ui.dp(this, 10);
        exitFab.setLayoutParams(flp);
        exitFab.setVisibility(View.GONE);
        exitFab.setOnClickListener(v -> setFullscreen(false));
        root.addView(exitFab);
    }

    private void buildWebView() {
        web = findViewById(R.id.web);
        styleWebView(web);
        // Rebuild the key row now that the WebView exists.
        KeyStrip.populate(keyStrip, web, pal);
        web.loadUrl(pendingUrl != null ? pendingUrl : server.url);
    }

    /** Re-creates the WebView after Android killed its renderer process. */
    private void rebuildWebView() {
        FrameLayout container = findViewById(R.id.web_container);
        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(web, 0);
        styleWebView(web);
        KeyStrip.populate(keyStrip, web, pal);
        web.loadUrl(pendingUrl != null ? pendingUrl : server.url);
    }

    private void styleWebView(WebView web) {
        web.setBackgroundColor(pal.bg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setKeepScreenOn(prefs.keepAwake());

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(userAgent(s));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) ->
                Downloads.start(this, url, ua, cd, mime, server));

        // Keep the extra-key row above the on-screen keyboard instead of under it.
        web.setOnFocusChangeListener((v, hasFocus) -> updateKeyStrip());
    }

    /**
     * code-server serves the same app to mobile and desktop, but VS Code's layout switches on the
     * user agent. A desktop UA on DeX (and even on a phone) gives the real editor chrome instead of
     * the cramped touch layout.
     */
    private String userAgent(WebSettings s) {
        String ua = WebSettings.getDefaultUserAgent(this);
        if (!prefs.desktopUserAgent()) return ua;
        String desktop = ua.replaceAll("Android [0-9._]+; ", "")
                .replace("; wv", "")
                .replace("Mobile Safari", "Safari");
        return desktop + " CodeServerGo/" + Prefs.versionName(this);
    }

    // ---------------------------------------------------------- layout modes

    private boolean isWide() {
        Configuration c = getResources().getConfiguration();
        return c.screenWidthDp >= 720;
    }

    private boolean hasPhysicalKeyboard() {
        return getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS;
    }

    private void updateRail() {
        boolean show = isWide() && !fullscreen;
        rail.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        EditorRail.populate(rail, store.all(), server.id, pal,
                s -> switchServer(s),
                s -> Ui.toast(this, s.name + "\n" + s.url),
                () -> startActivity(new Intent(this, HomeActivity.class)));
    }

    private void updateKeyStrip() {
        boolean show = prefs.extraKeys() && !fullscreen && !hasPhysicalKeyboard();
        keyStrip.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (!uiReady || web == null) return;
        updateRail();
        updateKeyStrip();
        // A DeX window can change DP size while the editor keeps running: nothing reloads.
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        topBar.setVisibility(on ? View.GONE : View.VISIBLE);
        progress.setVisibility(on || firstLoadDone ? View.GONE : View.VISIBLE);
        exitFab.setVisibility(on ? View.VISIBLE : View.GONE);
        updateRail();
        updateKeyStrip();

        Window w = getWindow();
        if (on) {
            flashFullscreenHint();
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            Ui.tint(btnFullscreen, 0xFFFFFFFF);
        } else {
            w.getDecorView().setSystemUiVisibility(0);
            applyPalette();
        }
    }

    /** Brief fading hint shown when full screen starts, then hides itself. */
    private void flashFullscreenHint() {
        if (fullscreenHint != null) {
            root.removeView(fullscreenHint);
            fullscreenHint = null;
        }
        TextView hint = Ui.text(this, "Esc exit · F5 reload", pal.text, 12f, false);
        hint.setBackground(Ui.round(this, 0x99000000, 22));
        int pad = Ui.dp(this, 12);
        int pv = Ui.dp(this, 7);
        hint.setPadding(pad, pv, pad, pv);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = Ui.dp(this, 24);
        lp.bottomMargin = Ui.dp(this, 24);
        hint.setLayoutParams(lp);
        hint.setAlpha(0f);
        root.addView(hint);
        hint.animate().alpha(0.9f).setDuration(220).start();
        fullscreenHint = hint;
        ui.postDelayed(() -> {
            if (fullscreenHint == null) return;
            fullscreenHint.animate().alpha(0f).setDuration(260).withEndAction(() -> {
                if (fullscreenHint != null) {
                    root.removeView(fullscreenHint);
                    fullscreenHint = null;
                }
            }).start();
        }, 1600);
    }

    private void toggleKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        View focus = getCurrentFocus();
        if (imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(
                    focus != null ? focus.getWindowToken() : web.getWindowToken(), 0);
        } else {
            web.requestFocus();
            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        }
        updateKeyStrip();
    }

    // ------------------------------------------------------------------- web

    private final class Client extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pendingUrl = url;
            setStatus(pal.warn, getString(R.string.loading));
            if (!firstLoadDone) showLoading(getString(R.string.loading));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            firstLoadDone = true;
            progress.setVisibility(fullscreen ? View.GONE : View.INVISIBLE);
            progress.setProgress(0);
            hideOverlay();
            applyZoom();
            updateNav();
            setStatus(pal.ok, server.host());
            if (txtTitle.getText() == null || txtTitle.getText().length() == 0) {
                txtTitle.setText(server.name);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request == null || request.isForMainFrame()) {
                showError(error != null ? String.valueOf(error.getDescription())
                        : "network error");
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                                    String failingUrl) {
            // Only the legacy callback fires on older WebView builds for main-frame failures.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showError(description);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handleSsl(handler, error);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                              String host, String realm) {
            handleAuth(handler, host, realm);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            root.removeView(view);
            web = null;
            showError("The editor's web renderer was restarted by Android (low memory).");
            return true;
        }
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (scheme.equals("http") || scheme.equals("https")) return false;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Ui.toast(this, "No app can open " + scheme + ": links");
        }
        return true;
    }

    private final class Chrome extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (fullscreen) return;
            if (newProgress < 100) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(Math.max(4, newProgress));
            } else {
                progress.setVisibility(View.INVISIBLE);
                progress.setProgress(0);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.trim().isEmpty()) {
                txtTitle.setText(title.length() > 90 ? title.substring(0, 90) + "\u2026" : title);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            pickFiles(params);
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            // VS Code may ask for a popup (external links). Hand it to the system browser.
            final WebView temp = new WebView(BrowserActivity.this);
            temp.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    openExternal(req.getUrl());
                    temp.destroy();
                    return true;
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    openExternal(Uri.parse(url));
                    temp.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // A code editor has no business with the camera or microphone.
            request.deny();
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Ui.toast(this, "No browser available");
        }
    }

    private void updateNav() {
        if (web == null) return;
        btnBack.setAlpha(web.canGoBack() ? 1f : 0.35f);
        btnForward.setAlpha(web.canGoForward() ? 1f : 0.35f);
    }

    private void setStatus(int color, String label) {
        statusDot.setBackground(Ui.dot(this, color));
        String chip = null;
        if (label != null && !label.isEmpty()) {
            if (label.equals(getString(R.string.offline))) {
                chip = "offline";
            } else if (label.equals(getString(R.string.connected)) || label.equals(getString(R.string.loading))
                    || label.equals(server.host())) {
                chip = label.equals(getString(R.string.connected)) ? "ok" : null;
            }
            setTitleTextIfPlaceholder(label);
        }
        if (txtStatus != null) {
            if (chip != null) {
                txtStatus.setText(chip);
                txtStatus.setTextColor(chip.equals("offline") ? pal.danger : pal.ok);
                txtStatus.setVisibility(View.VISIBLE);
            } else {
                txtStatus.setVisibility(View.GONE);
            }
        }
    }

    private void setTitleTextIfPlaceholder(String label) {
        // The status line shares the title slot on narrow windows; only fill it when empty.
        if (txtTitle.getText() == null || txtTitle.getText().length() == 0
                || txtTitle.getText().toString().equals(getString(R.string.connecting))) {
            txtTitle.setText(label);
        }
    }

    // --------------------------------------------------------------- overlay

    private void showLoading(String label) {
        overlay.showLoading(label);
    }

    private void showError(String detail) {
        overlay.showError(detail, getString(R.string.error_title));
        overlay.setErrorBody(getString(R.string.error_body, detail == null ? "" : detail));
        overlay.setRetry(getString(R.string.retry), () -> {
            showLoading(getString(R.string.connecting));
            firstLoadDone = false;
            if (web != null) {
                web.reload();
            } else {
                rebuildWebView();
            }
        });
        progress.setVisibility(View.INVISIBLE);
        setStatus(pal.danger, getString(R.string.offline));
        txtTitle.setText(server.name);
    }

    private void hideOverlay() {
        overlay.hide();
    }

    // ------------------------------------------------------------------- tls

    private void handleSsl(final SslErrorHandler handler, final SslError error) {
        final String host = error.getUrl() != null ? Uri.parse(error.getUrl()).getHost() : server.host();
        final String fingerprint = fingerprint(error.getCertificate());
        final String reason = sslReason(error.getPrimaryError());

        if (fingerprint != null && prefs.isTrusted(host, fingerprint)) {
            handler.proceed();
            return;
        }

        SslCertificate cert = error.getCertificate();
        String subject = cert != null ? String.valueOf(cert.getIssuedTo()) : "unknown";
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ssl, null);
        TextView body = view.findViewById(R.id.txt_body);
        body.setText(getString(R.string.ssl_body, host, subject + "\n\n" + reason,
                fingerprint == null ? "unavailable" : fingerprint));

        new AlertDialog.Builder(this)
                .setTitle(R.string.ssl_title)
                .setView(view)
                .setPositiveButton(R.string.ssl_trust, (d, w) -> {
                    if (fingerprint != null) prefs.trust(host, fingerprint);
                    handler.proceed();
                })
                .setNeutralButton(R.string.ssl_once, (d, w) -> handler.proceed())
                .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    private static String sslReason(int primaryError) {
        switch (primaryError) {
            case SslError.SSL_UNTRUSTED:
                return "Certificate issued by an unknown authority.";
            case SslError.SSL_EXPIRED:
                return "Certificate has expired.";
            case SslError.SSL_IDMISMATCH:
                return "Certificate does not match this host name.";
            case SslError.SSL_NOTYETVALID:
                return "Certificate is not valid yet.";
            case SslError.SSL_DATE_INVALID:
                return "Certificate date is invalid.";
            default:
                return "TLS handshake rejected.";
        }
    }

    /** SHA-256 of the DER certificate, formatted like a browser shows it. */
    private static String fingerprint(SslCertificate cert) {
        if (cert == null) return null;
        try {
            Bundle state = SslCertificate.saveState(cert);
            byte[] der = null;
            if (state != null) {
                for (String key : state.keySet()) {
                    Object value = state.get(key);
                    if (value instanceof byte[]) {
                        der = (byte[]) value;
                        break;
                    }
                }
            }
            if (der == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(der);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // Last resort: hash the human readable identity of the certificate.
            try {
                String identity = cert.getIssuedTo() + "|" + cert.getIssuedBy()
                        + "|" + cert.getValidNotAfter();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return "ID:" + Base64.encodeToString(
                        md.digest(identity.getBytes("UTF-8")), Base64.NO_WRAP);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ auth

    private void handleAuth(final HttpAuthHandler handler, String host, String realm) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_auth, null);
        final EditText user = view.findViewById(R.id.in_user);
        final EditText pass = view.findViewById(R.id.in_pass);

        new AlertDialog.Builder(this)
                .setTitle(R.string.auth_title)
                .setMessage(getString(R.string.auth_body, host + (realm == null ? "" : " (" + realm + ")")))
                .setView(view)
                .setPositiveButton(R.string.save, (d, w) ->
                        handler.proceed(user.getText().toString(), pass.getText().toString()))
                .setNegativeButton(R.string.cancel, (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    // -------------------------------------------------------------- zoom/fit

    private void applyZoom() {
        if (web == null) return;
        int pct = Math.round(zoom * 100f);
        web.evaluateJavascript(
                "(function(){try{document.documentElement.style.zoom='" + pct + "%';}catch(e){}})();",
                null);
    }

    private void setZoom(float next) {
        zoom = Math.max(0.5f, Math.min(2f, next));
        prefs.setZoom(server.id, zoom);
        applyZoom();
        Ui.toast(this, "Zoom " + Math.round(zoom * 100) + "%");
    }

    // ------------------------------------------------------------ file input

    private void pickFiles(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            String accept = params != null && params.getAcceptTypes() != null
                    ? params.getAcceptTypes()[0] : null;
            if (accept != null && (accept.contains("/") || accept.equals("*/*"))) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes());
                if (!accept.equals("*/*")) intent.setType(accept);
            }
        } catch (Exception ignored) {
        }

        String startDir = prefs.uploadDir();
        if (startDir == null) {
            startDir = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Projects").toString();
        }
        try {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(startDir));
        } catch (Exception ignored) {
        }

        try {
            startActivityForResult(intent, REQ_PICK_FILES);
        } catch (Exception e) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
                fileCallback = null;
            }
            Ui.toast(this, "No file manager available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILES) return;

        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
            if (uris != null && uris.length > 0) rememberParent(uris[0]);
        }

        if (fileCallback != null) {
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        }
    }

    /** Remembers the folder so the next upload starts where you left off (/sdcard/Projects). */
    private void rememberParent(Uri uri) {
        try {
            for (Uri u : new Uri[]{uri}) {
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
            }
            if (!DocumentsContract.isDocumentUri(this, uri)) return;
            String docId = DocumentsContract.getDocumentId(uri);
            int slash = docId.lastIndexOf('/');
            String parent = slash > 0 ? docId.substring(0, slash) : docId;
            Uri dir = DocumentsContract.buildDocumentUri(uri.getAuthority(), parent);
            prefs.setUploadDir(dir.toString());
        } catch (Exception ignored) {
        }
    }

    // ----------------------------------------------------------------- menus

    private void showServerMenu(View anchor) {
        List<Server> servers = store.all();
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            menu.getMenu().add(0, 100 + i, i, s.name + "  ·  " + s.host());
        }
        menu.getMenu().add(1, 900, 900, getString(R.string.servers_title) + "\u2026");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 900) {
                startActivity(new Intent(this, HomeActivity.class));
                return true;
            }
            int index = item.getItemId() - 100;
            if (index >= 0 && index < servers.size()) switchServer(servers.get(index));
            return true;
        });
        menu.show();
    }

    private void switchServer(Server s) {
        if (s == null || s.id.equals(server.id)) return;
        server = s;
        pendingUrl = s.url;
        prefs.setLastServerId(s.id);
        store.touch(s.id);
        zoom = prefs.zoom(s.id);
        firstLoadDone = false;
        txtTitle.setText(s.name);
        updateRail();
        showLoading(getString(R.string.connecting));
        if (web == null) {
            rebuildWebView();
        } else {
            web.loadUrl(s.url);
        }
    }

    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Reload without cache");
        menu.getMenu().add(0, 2, 1, R.string.open_external);
        menu.getMenu().add(0, 3, 2, R.string.upload);
        menu.getMenu().add(0, 4, 3, R.string.copy_url);
        menu.getMenu().add(0, 5, 4, "Zoom in (" + Math.round(zoom * 100) + "%)");
        menu.getMenu().add(0, 6, 5, "Zoom out");
        menu.getMenu().add(0, 7, 6, R.string.zoom_reset);
        menu.getMenu().add(0, 8, 7, getString(R.string.desktop_mode))
                .setCheckable(true).setChecked(prefs.desktopUserAgent());
        menu.getMenu().add(0, 9, 8, getString(R.string.keep_awake))
                .setCheckable(true).setChecked(prefs.keepAwake());
        menu.getMenu().add(0, 10, 9, getString(R.string.extra_keys))
                .setCheckable(true).setChecked(prefs.extraKeys());
        menu.getMenu().add(0, 11, 10, "Clear cookies for this host");
        menu.getMenu().add(0, 12, 11, R.string.fullscreen);

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    web.clearCache(true);
                    firstLoadDone = false;
                    showLoading(getString(R.string.connecting));
                    web.reload();
                    return true;
                case 2:
                    openExternal(Uri.parse(pendingUrl != null ? pendingUrl : server.url));
                    return true;
                case 3:
                    Ui.toast(this, getString(R.string.upload_hint));
                    return true;
                case 4:
                    copyAddress();
                    return true;
                case 5:
                    setZoom(zoom + 0.1f);
                    return true;
                case 6:
                    setZoom(zoom - 0.1f);
                    return true;
                case 7:
                    setZoom(1f);
                    return true;
                case 8:
                    prefs.setDesktopUserAgent(!prefs.desktopUserAgent());
                    WebSettings ws = web.getSettings();
                    ws.setUserAgentString(userAgent(ws));
                    web.reload();
                    Ui.toast(this, prefs.desktopUserAgent() ? "Desktop user agent on"
                            : "Mobile user agent on");
                    return true;
                case 9:
                    prefs.setKeepAwake(!prefs.keepAwake());
                    web.setKeepScreenOn(prefs.keepAwake());
                    Ui.toast(this, prefs.keepAwake() ? "Screen will stay on"
                            : "Screen may sleep");
                    return true;
                case 10:
                    prefs.setExtraKeys(!prefs.extraKeys());
                    updateKeyStrip();
                    return true;
                case 11:
                    clearCookies();
                    return true;
                case 12:
                    setFullscreen(true);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void copyAddress() {
        String url = web != null ? web.getUrl() : pendingUrl;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && url != null) {
            cm.setPrimaryClip(ClipData.newPlainText("code-server", url));
            Ui.toast(this, getString(R.string.copied));
        }
    }

    /** Expires every cookie of this origin, then reloads to the login page. */
    private void clearCookies() {
        CookieManager cm = CookieManager.getInstance();
        String url = server.origin() + "/";
        String cookies = cm.getCookie(url);
        int removed = 0;
        if (cookies != null) {
            for (String pair : cookies.split(";")) {
                String name = pair.split("=")[0].trim();
                if (name.isEmpty()) continue;
                cm.setCookie(url, name + "=; Max-Age=0; path=/");
                cm.setCookie(url, name + "=; Max-Age=0; path=/; domain=" + Uri.parse(url).getHost());
                cm.setCookie(server.origin() + "/", name + "=; Max-Age=0; path=/");
                removed++;
            }
        }
        cm.flush();
        Ui.toast(this, removed > 0 ? "Cleared " + removed + " cookie(s) — reloading"
                : getString(R.string.site_data_cleared));
        firstLoadDone = false;
        showLoading(getString(R.string.connecting));
        web.reload();
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2200) {
            super.onBackPressed();
            return;
        }
        lastBackPress = now;
        Ui.toast(this, getString(R.string.back_exit_hint));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            boolean alt = (event.getMetaState() & KeyEvent.META_ALT_ON) != 0;
            boolean ctrl = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
            if (alt && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (web != null && web.canGoBack()) web.goBack();
                return true;
            }
            if (alt && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (web != null && web.canGoForward()) web.goForward();
                return true;
            }
            if (key == KeyEvent.KEYCODE_F5 || (ctrl && key == KeyEvent.KEYCODE_R)) {
                if (web != null) {
                    firstLoadDone = false;
                    web.reload();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_ESCAPE && fullscreen) {
                setFullscreen(false);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Server s = store.get(intent.getStringExtra(EXTRA_SERVER_ID));
        if (s != null) switchServer(s);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUrl != null) out.putString(STATE_URL, pendingUrl);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
            CookieManager.getInstance().flush();
        }
        if (ui != null) ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!uiReady) return;
        if (web != null) web.onResume();
        if (!prefs.isCurrentTheme(pal.dark)) {
            recreate();
            return;
        }
        updateKeyStrip();
        updateRail();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
