package dev.codeservergo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Launcher screen: the list of code-server endpoints.
 *
 * <p>Tablet / DeX friendly: content is centred and capped in width so rows stay readable on a
 * wide desktop-sized window instead of stretching edge to edge.
 */
public class HomeActivity extends Activity {

    private Prefs prefs;
    private ServerStore store;
    private Palette pal;
    private LinearLayout list;
    private LinearLayout content;
    private int basePadStart;
    private int basePadEnd;
    private boolean uiReady;
    private LinearLayout localBox;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        prefs = new Prefs(this);
        store = new ServerStore(this);
        pal = Palette.forContext(this, prefs);
        setTheme(pal.dark ? R.style.Theme_CodeServerGo : R.style.Theme_CodeServerGo_Light);
        super.onCreate(state);
        try {
            buildUi();
            uiReady = true;
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private void buildUi() {
        setContentView(R.layout.activity_home);

        content = findViewById(R.id.content);
        list = findViewById(R.id.list_container);
        localBox = findViewById(R.id.local_box);
        basePadStart = content.getPaddingStart();
        basePadEnd = content.getPaddingEnd();

        findViewById(R.id.btn_add).setOnClickListener(v -> showServerDialog(null));
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());

        applyPalette();
        applyWidth();
    }

    /** Safe mode: a crash while starting up becomes readable instead of a silent exit. */
    private void showFatal(Throwable t) {
        CrashLog.saveNow(this, t);
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        TextView view = Ui.text(this,
                "CodeServer Go could not start\n\n" + sw + "\n\nReport saved to "
                        + "Download/CodeServerGo/", 0xFFFF9E9E, 11f, false);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        view.setPadding(p, p, p, p);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(0xFF14171C);
        scroll.addView(view);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!uiReady) return;
        if (!prefs.isCurrentTheme(pal.dark)) {
            recreate();
            return;
        }
        applyPalette();
        renderLocal();
        render();
        if (prefs.shouldCheckForUpdate()) checkForUpdates(false);
    }

    // ------------------------------------------------------------------ look

    private void applyPalette() {
        Window w = getWindow();
        w.setBackgroundDrawableResource(pal.dark ? R.color.bg : R.color.bg_light);
        w.setStatusBarColor(pal.bg);
        w.setNavigationBarColor(pal.bg);

        ((TextView) findViewById(R.id.title)).setTextColor(pal.text);
        ((TextView) findViewById(R.id.subtitle)).setTextColor(pal.textDim);

        View root = findViewById(R.id.root);
        root.setBackgroundColor(pal.bg);

        Ui.tint((ImageButton) findViewById(R.id.btn_add), pal.text);
        Ui.tint((ImageButton) findViewById(R.id.btn_settings), pal.text);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = w.getDecorView().getSystemUiVisibility();
            if (pal.dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void applyWidth() {
        int wdp = getResources().getConfiguration().screenWidthDp;
        int extra = Math.max(0, (wdp - 820) / 2);
        content.setPadding(basePadStart + Ui.dp(this, extra), content.getPaddingTop(),
                basePadEnd + Ui.dp(this, extra), content.getPaddingBottom());
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!uiReady) return;
        applyWidth();
        applyPalette();
    }

    // -------------------------------------------------------- local runtime

    private void renderLocal() {
        if (localBox == null) return;
        localBox.removeAllViews();

        final boolean installed = Runtime.installed(this);
        final boolean running = LocalServer.running();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundCard());
        int p = Ui.dp(this, 16);
        card.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(this, getString(R.string.local_title), pal.text, 15f, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        View dot = new View(this);
        int ds = Ui.dp(this, 8);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.dot(this, running ? pal.ok : installed ? pal.warn : pal.dotIdle));
        head.addView(dot);
        card.addView(head);

        String status = !installed ? getString(R.string.local_not_installed)
                : running ? getString(R.string.local_running)
                : getString(R.string.local_installed_stopped);
        TextView sub = Ui.text(this, status, pal.textDim, 12f, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(sub);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 12);
        buttons.setLayoutParams(blp);

        if (!installed) {
            buttons.addView(actionPill(getString(R.string.local_setup), true,
                    v -> confirmSetup()));
        } else if (running) {
            buttons.addView(actionPill(getString(R.string.local_open), true, v -> {
                Server s = ensureLocalServer();
                if (s != null) open(s);
            }));
            buttons.addView(actionPill(getString(R.string.local_stop), false, v -> {
                LocalServer.stop(this);
                renderLocal();
            }));
            buttons.addView(actionPill(getString(R.string.local_log), false,
                    v -> showLogDialog(LocalServer.tail(120))));
            buttons.addView(actionPill(getString(R.string.local_diagnose), false,
                    v -> runDiagnostics()));
        } else {
            buttons.addView(actionPill(getString(R.string.local_update), false,
                    v -> confirmUpdateCodeServer()));
            buttons.addView(actionPill(getString(R.string.local_start), true,
                    v -> startLocalServer()));
            buttons.addView(actionPill(getString(R.string.local_repair), false,
                    v -> installRuntime()));
            buttons.addView(actionPill(getString(R.string.local_log), false,
                    v -> showLogDialog(LocalServer.tail(120))));
            buttons.addView(actionPill(getString(R.string.local_diagnose), false,
                    v -> runDiagnostics()));
            buttons.addView(actionPill(getString(R.string.local_remove), false,
                    v -> confirmRemove()));
        }
        card.addView(buttons);
        localBox.addView(card);
    }

    private View actionPill(String label, boolean primary, View.OnClickListener onClick) {
        TextView t = Ui.text(this, label, primary ? Color.WHITE : pal.text, 13f, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(primary ? Ui.round(this, pal.accent, 11)
                : Ui.roundStroke(this, pal.surfaceAlt, Ui.alpha(0xFFFFFFFF, 0.08f), 11));
        int ph = Ui.dp(this, 16);
        int pv = Ui.dp(this, 9);
        t.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        t.setOnClickListener(onClick);
        return t;
    }

    private void confirmSetup() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.local_setup_title)
                .setMessage(R.string.local_setup_body)
                .setPositiveButton(R.string.local_setup_ok, (d, w) -> installRuntime())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void installRuntime() {
        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        final TextView label = Ui.text(this, getString(R.string.local_installing), pal.textDim,
                13f, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 12), p, 0);
        box.addView(label);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 12);
        bar.setLayoutParams(blp);
        box.addView(bar);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_setup_title)
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            RuntimeInstaller.install(this, new RuntimeInstaller.Callback() {
                @Override
                public void onProgress(final String stage, final float fraction) {
                    ui.post(() -> {
                        label.setText(stage + "  " + Math.round(fraction * 100) + "%");
                        bar.setProgress((int) (fraction * 1000));
                    });
                }

                @Override
                public void onDone(final boolean ok, final String message) {
                    final HomeActivity self = HomeActivity.this;
                    ui.post(() -> {
                        dialog.dismiss();
                        if (ok) {
                            Ui.toast(self, "Local editor installed");
                        } else {
                            CrashLog.exportText(self, "local-install.log", message + "\n\n"
                                    + LocalServer.tail(150));
                            self.showLogDialog(message + "\n\n" + LocalServer.tail(60));
                        }
                        self.renderLocal();
                    });
                }
            });
        }, "runtime-install").start();
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.local_remove_body)
                .setPositiveButton(R.string.local_remove, (d, w) -> {
                    LocalServer.stop(this);
                    RuntimeInstaller.deleteRecursive(Runtime.base(this));
                    Ui.toast(this, "Local runtime removed");
                    renderLocal();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startLocalServer() {
        if (PackageUtils.needsAllFilesAccess(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Storage access needed")
                    .setMessage("Android does not let apps list files on /sdcard without all-files access. In Settings, enable “Allow management of all files” for CodeServer Go, then come back and tap Start again.")
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            PackageUtils.openAllFilesAccessSettings(this))
                    .show();
            return;
        }
        final ProgressBar spinner = new ProgressBar(this);
        final TextView label = Ui.text(this, getString(R.string.local_starting), pal.textDim,
                13f, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 8));
        int s = Ui.dp(this, 28);
        spinner.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        box.addView(spinner);
        label.setPadding(Ui.dp(this, 14), 0, 0, 0);
        box.addView(label);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            String error = null;
            try {
                LocalServer.start(this);
                if (!LocalServer.waitHealthy(150000)) {
                    error = "editor did not answer on port " + Runtime.PORT;
                }
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                dialog.dismiss();
                if (err == null) {
                    Server srv = ensureLocalServer();
                    if (srv != null) open(srv);
                } else {
                    CrashLog.exportText(this, "local-server.log",
                            "ERROR: " + err + "\n\n" + LocalServer.tail(250));
                    showLogDialog(getString(R.string.local_failed) + "\n\n" + err + "\n\n"
                            + LocalServer.tail(60));
                }
                renderLocal();
            });
        }, "local-start").start();
    }

    /** Creates (or reuses) the server entry that points at the local runtime. */
    private Server ensureLocalServer() {
        String localUrl = Runtime.url();
        for (Server s : store.all()) {
            if (s.url != null && s.url.contains("127.0.0.1:" + Runtime.PORT)) return s;
        }
        Server s = new Server();
        s.name = getString(R.string.local_title);
        s.url = localUrl;
        store.upsert(s);
        return s;
    }

    /**
     * Tries several proot configurations and saves the whole report to
     * Download/CodeServerGo/diagnostics.log — the only way to debug this without adb.
     */
    private void runDiagnostics() {
        Ui.toast(this, "Probing proot\u2026");
        new Thread(() -> {
            String report = Probe.run(this);
            ui.post(() -> showLogDialog(report));
        }, "diagnose").start();
    }

    private void showLogDialog(String text) {
        TextView view = Ui.text(this, text == null || text.isEmpty() ? "(no output yet)" : text,
                pal.textDim, 11f, false);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int p = Ui.dp(this, 14);
        view.setPadding(p, p, p, p);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(R.string.local_log)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ------------------------------------------------------------------ list

    private void render() {
        List<Server> servers = store.all();
        list.removeAllViews();

        if (servers.isEmpty()) {
            list.addView(emptyState());
            return;
        }

        list.addView(sectionLabel(getString(R.string.recent) + " · " + servers.size()));
        for (Server s : servers) list.addView(row(s));
        list.addView(footerHint());
    }

    private View sectionLabel(String text) {
        TextView t = Ui.text(this, text.toUpperCase(), pal.textDim, 11f, true);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        lp.bottomMargin = Ui.dp(this, 8);
        lp.leftMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    private View row(final Server s) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_server, list, false);
        v.setBackground(roundCard());
        v.setForeground(Ui.ripple(this, pal.accent, 14));

        TextView name = v.findViewById(R.id.name);
        TextView url = v.findViewById(R.id.url);
        ImageView icon = v.findViewById(R.id.icon);
        ImageButton more = v.findViewById(R.id.more);
        View dot = v.findViewById(R.id.dot);

        name.setText(s.name);
        name.setTextColor(pal.text);
        url.setText(s.url);
        url.setTextColor(pal.textDim);
        icon.setColorFilter(pal.accent);
        Ui.tint(more, pal.textDim);

        if (s.id.equals(prefs.lastServerId())) {
            dot.setVisibility(View.VISIBLE);
            dot.setBackground(Ui.dot(this, pal.ok));
        }

        v.setOnClickListener(x -> open(s));
        v.setOnLongClickListener(x -> {
            rowMenu(s, more);
            return true;
        });
        more.setOnClickListener(x -> rowMenu(s, more));
        return v;
    }

    private android.graphics.drawable.Drawable roundCard() {
        return Ui.roundStroke(this, pal.surface, Ui.alpha(0xFFFFFFFF, pal.dark ? 0.08f : 0.06f), 14);
    }

    private View emptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 24);
        box.setLayoutParams(lp);
        box.setBackground(roundCard());
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, p);

        TextView title = Ui.text(this, getString(R.string.empty_title), pal.text, 17f, true);
        box.addView(title);

        TextView body = Ui.text(this, getString(R.string.empty_body), pal.textDim, 13f, false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 10);
        body.setLayoutParams(blp);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(body);

        TextView action = Ui.text(this, getString(R.string.empty_action), Color.WHITE, 14f, true);
        action.setGravity(android.view.Gravity.CENTER);
        action.setBackground(Ui.round(this, pal.accent, 12));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        alp.topMargin = Ui.dp(this, 18);
        action.setLayoutParams(alp);
        action.setOnClickListener(v -> showServerDialog(null));
        box.addView(action);
        return box;
    }

    private View footerHint() {
        TextView t = Ui.text(this, getString(R.string.home_hint_dex), pal.textDim, 12f, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        lp.leftMargin = Ui.dp(this, 4);
        lp.rightMargin = Ui.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
    }

    private void open(Server s) {
        store.touch(s.id);
        prefs.setLastServerId(s.id);
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SERVER_ID, s.id);
        startActivity(i);
    }

    private void rowMenu(final Server s, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.edit_server);
        menu.getMenu().add(0, 2, 1, R.string.open_external);
        menu.getMenu().add(0, 3, 2, R.string.delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showServerDialog(s);
            } else if (item.getItemId() == 2) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s.url)));
                } catch (Exception e) {
                    Ui.toast(this, getString(R.string.invalid_url));
                }
            } else if (item.getItemId() == 3) {
                new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_server_confirm, s.name))
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            store.delete(s.id);
                            if (s.id.equals(prefs.lastServerId())) prefs.setLastServerId(null);
                            render();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }

    // --------------------------------------------------------------- dialogs

    private void showServerDialog(final Server existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_server, null);
        final EditText inName = view.findViewById(R.id.in_name);
        final EditText inUrl = view.findViewById(R.id.in_url);
        if (existing != null) {
            inName.setText(existing.name);
            inUrl.setText(existing.url);
        } else {
            inUrl.setText("http://192.168.");
        }
        ((TextView) view.findViewById(R.id.txt_hint)).setTextColor(pal.textDim);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_server : R.string.edit_server)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = Server.normalize(inUrl.getText().toString());
                if (url == null) {
                    inUrl.setError(getString(R.string.invalid_url));
                    return;
                }
                Server s = existing != null ? existing : new Server();
                s.url = url;
                s.name = Server.nameOrHost(inName.getText().toString(), url);
                store.upsert(s);
                dialog.dismiss();
                render();
            });
        });

        inUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });

        dialog.show();
        inName.requestFocus();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 4));

        box.addView(settingHeader(getString(R.string.appearance)));
        box.addView(settingRow(getString(R.string.appearance), themeLabel(), v -> pickTheme()));

        box.addView(settingHeader(getString(R.string.behaviour)));
        box.addView(settingSwitch(getString(R.string.desktop_mode), prefs.desktopUserAgent(),
                checked -> prefs.setDesktopUserAgent(checked)));
        box.addView(settingSwitch(getString(R.string.extra_keys), prefs.extraKeys(),
                checked -> prefs.setExtraKeys(checked)));
        box.addView(settingSwitch(getString(R.string.keep_awake), prefs.keepAwake(),
                checked -> prefs.setKeepAwake(checked)));

        box.addView(settingRow("Trusted certificates", "Forget all pinned fingerprints", v -> {
            prefs.forgetTrusted();
            Ui.toast(this, "Trusted certificates cleared");
        }));

        box.addView(settingRow("Storage access",
                PackageUtils.needsAllFilesAccess(this)
                        ? "All-files access not granted" : "All-files access granted",
                v -> PackageUtils.openAllFilesAccessSettings(this)));
        box.addView(settingRow(getString(R.string.check_updates),
                "Last checked: " + lastCheckLabel(), v -> checkForUpdates(true)));
        box.addView(settingRow(getString(R.string.about), getString(R.string.version,
                Prefs.versionName(this)), v -> showAbout()));

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(R.string.close, null)
                .create();
        d.show();
    }

    // -------------------------------------------------------------- updates

    private String lastCheckLabel() {
        long last = prefs.lastUpdateCheckMs();
        if (last <= 0) return "never";
        long ago = System.currentTimeMillis() - last;
        long min = Math.max(1, ago / 60000);
        if (min < 60) return min + " min ago";
        long hr = min / 60;
        if (hr < 48) return hr + " hr ago";
        return (hr / 24) + " days ago";
    }

    private void checkForUpdates(final boolean manual) {
        if (manual) Ui.toast(this, "Checking for updates\u2026");
        UpdateChecker.check(this, manual, new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(final UpdateChecker.ReleaseInfo info) {
                String message = getString(R.string.update_available,
                        Prefs.versionName(HomeActivity.this), info.versionName);
                if (info.notes != null && !info.notes.isEmpty()) {
                    message += "\n\n" + info.notes;
                }
                new AlertDialog.Builder(HomeActivity.this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.update_download, (d, w) -> downloadUpdate(info))
                        .setNegativeButton(R.string.update_later, null)
                        .show();
            }

            @Override
            public void onUpToDate(String currentVersionName, long currentVersionCode) {
                if (manual) {
                    Ui.toast(HomeActivity.this, getString(R.string.update_uptodate,
                            currentVersionName));
                }
            }

            @Override
            public void onError(String message) {
                if (manual) {
                    Ui.toast(HomeActivity.this,
                            "Update check failed: " + message);
                }
            }
        });
    }

    private void downloadUpdate(final UpdateChecker.ReleaseInfo info) {
        Ui.toast(this, getString(R.string.update_downloading, info.versionName));
        UpdateChecker.downloadApk(this, info, new UpdateChecker.Callback2() {
            @Override
            public void onDownloaded(final android.net.Uri contentUri) {
                ui.post(() -> installApk(contentUri));
            }

            @Override
            public void onFailed(final String message) {
                ui.post(() -> Ui.toast(HomeActivity.this,
                        "Download failed: " + message));
            }
        });
    }

    /** Hands the downloaded APK to the system package installer. */
    /** Hands the downloaded APK to the system package installer. */
    private void installApk(android.net.Uri contentUri) {
        if (PackageUtils.needsUnknownSourcesPermission(this)) {
            requestUnknownSources(contentUri);
            return;
        }
        launchInstaller(contentUri);
    }

    /** If unknown-apps permission is off, explain exactly what is missing. */
    private void requestUnknownSources(final android.net.Uri contentUri) {
        new AlertDialog.Builder(this)
                .setTitle("Allow installs from this app")
                .setMessage("Android blocks installing updates unless you allow it.\n\n"
                        + "Tap Continue, enable \u201cAllow from this source\u201d for "
                        + "CodeServer Go, then come back.")
                .setPositiveButton("Continue",
                        (d, w) -> PackageUtils.openUnknownSourcesSettings(this))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Actually starts the Android installer. */
    private void launchInstaller(android.net.Uri contentUri) {
        try {
            Intent intent = new Intent(android.content.Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(contentUri, UpdateChecker.APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Throwable tInstall) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, UpdateChecker.APK_MIME);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Throwable tInstall2) {
                Ui.toast(this, "Installer did not start: " + tInstall2.getMessage());
            }
        }
    }

    // ----------------------------------------------- code-server version

    private void confirmUpdateCodeServer() {
        final TextView label = Ui.text(this, "Checking for newer code-server\u2026",
                pal.textDim, 13f, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 16), p, 0);
        box.addView(label);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Update code-server")
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            LocalEditorUpdate.Release latest = null;
            String err = null;
            try {
                latest = LocalEditorUpdate.fetchLatest();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            final LocalEditorUpdate.Release result = latest;
            final String error = err;
            ui.post(() -> {
                dialog.dismiss();
                if (error != null) {
                    Ui.toast(this, "Could not check GitHub: " + error);
                    return;
                }
                String installed = LocalEditorUpdate.installedVersion(this);
                String latestVersion = result != null ? result.version : "?";
                if (installed.equals(latestVersion)) {
                    Ui.toast(this, "Already on latest (" + installed + ")");
                    return;
                }
                if (result == null) {
                    Ui.toast(this, "Could not read the latest release");
                    return;
                }
                String size = result.assetBytes > 0
                        ? (" (" + (result.assetBytes / (1024 * 1024)) + " MB)") : "";
                new AlertDialog.Builder(this)
                        .setTitle("Update code-server")
                        .setMessage("Update from " + installed + "\u0020\u2192\u0020"
                                + latestVersion + size + "?\n\n"
                                + "Downloads and replaces the editor bundle in place.")
                        .setPositiveButton("Update",
                                (d, w) -> runCodeServerUpdate())
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }, "check-latest").start();
    }

    private void runCodeServerUpdate() {
        final TextView label = Ui.text(this, "Downloading…", pal.textDim, 13f, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 16), p, 0);
        box.addView(label);
        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 12);
        bar.setLayoutParams(blp);
        box.addView(bar);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Update code-server")
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> LocalEditorUpdate.update(this, new LocalEditorUpdate.Callback() {
            @Override
            public void onProgress(String stage, float fraction) {
                ui.post(() -> {
                    label.setText(stage);
                    bar.setProgress((int) (fraction * 1000));
                });
            }

            @Override
            public void onDone(boolean ok, String message) {
                ui.post(() -> {
                    dialog.dismiss();
                    Ui.toast(HomeActivity.this,
                            ok ? message : "Update failed: " + message);
                    renderLocal();
                });
            }
        }), "cs-update").start();
    }

    private String themeLabel() {
        switch (prefs.theme()) {
            case Prefs.THEME_DARK:
                return getString(R.string.theme_dark);
            case Prefs.THEME_LIGHT:
                return getString(R.string.theme_light);
            default:
                return getString(R.string.theme_auto);
        }
    }

    private void pickTheme() {
        final String[] options = {
                getString(R.string.theme_auto),
                getString(R.string.theme_dark),
                getString(R.string.theme_light)
        };
        int current = prefs.theme() == Prefs.THEME_DARK ? 1
                : prefs.theme() == Prefs.THEME_LIGHT ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.appearance)
                .setSingleChoiceItems(options, current, (d, which) -> {
                    prefs.setTheme(which == 0 ? Prefs.THEME_AUTO
                            : which == 1 ? Prefs.THEME_DARK : Prefs.THEME_LIGHT);
                    d.dismiss();
                    recreate();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(getString(R.string.about_body) + "\n\n"
                        + getString(R.string.version, Prefs.versionName(this)))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private View settingHeader(String text) {
        TextView t = Ui.text(this, text.toUpperCase(), pal.textDim, 11f, true);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        lp.bottomMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private View settingRow(String title, String summary, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pv = Ui.dp(this, 10);
        row.setPadding(0, pv, 0, pv);
        row.setClickable(true);
        row.setBackground(Ui.ripple(this, pal.accent, 10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(Ui.text(this, title, pal.text, 14f, false));
        if (summary != null && !summary.isEmpty()) {
            TextView s = Ui.text(this, summary, pal.textDim, 12f, false);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts);
        if (onClick != null) row.setOnClickListener(onClick);
        return row;
    }

    private View settingSwitch(String title, boolean value, final SwitchListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pv = Ui.dp(this, 6);
        row.setPadding(0, pv, 0, pv);

        TextView t = Ui.text(this, title, pal.text, 14f, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        final Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setButtonTintList(android.content.res.ColorStateList.valueOf(pal.accent));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(pal.accent));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(
                Ui.alpha(pal.accent, 0.4f)));
        sw.setOnCheckedChangeListener((b, checked) -> listener.onChanged(checked));
        row.addView(sw);
        return row;
    }

    interface SwitchListener {
        void onChanged(boolean checked);
    }
}
