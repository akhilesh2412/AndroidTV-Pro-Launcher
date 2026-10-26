package com.kulwant.atvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. All D-pad navigation is handled here (no Android focus search), so
 * movement is deterministic: zones TOP (pills) / DOCK / GRID, Up/Down move exactly one row.
 */
public class HomeActivity extends Activity implements Wallpapers.Listener {

    private enum Zone { TOP, DOCK, GRID }

    private static final int PILLS = 7;
    /** apps per row, dock and grid (config.xml: columns) */
    private int COLS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private int colText, colTextDim, colHeader, colCardLabel, colPillFill, colPillStroke, colPillText,
            colBand, colBandStroke;
    private int[] scrimColors;

    // layout (px)
    private int W, H, side, bandTop, gridTop, rowH;

    private FrameLayout root, content;
    private TextView greeting, clock, date, wifiText, hoverName, ramText;
    private LinearLayout pillRow;
    // follower counters under the pills: YouTube, Instagram, Facebook
    private static final String[] SOCIAL_KEYS = {"yt", "ig", "fb"};
    private static final String[] SOCIAL_UNITS = {"subscribers", "followers", "followers"};
    private final String[] socialIds = new String[3];
    private final View[] socialPills = new View[3];
    private final TextView[] socialTexts = new TextView[3];
    private boolean hasSocial;
    private static final long SUBS_REFRESH_MS = 30 * 60_000L;
    private final View[] pills = new View[PILLS];
    private GlassHighlight highlight;
    private View glow;
    private GradientDrawable glowBg;
    private RecyclerView dockRv, gridRv;
    private TileAdapter dockAdapter, gridAdapter;
    private InputsMenu inputs;
    private PowerMenu powerMenu;
    private AppMenu appMenu;
    /** Jarvis (v3.12.0, new file only): full-screen voice overlay, see Jarvis.java. */
    private Jarvis jarvis;
    private static final int MENU_NONE = 0, MENU_APP = 1, MENU_HIDDEN = 2;
    private int menuMode = MENU_NONE;
    private boolean dockScrollRetry;
    private AppRepo.App menuApp;
    private final List<AppRepo.App> hiddenShown = new ArrayList<>();
    private final Set<String> hiddenPkgs = new HashSet<>();
    /** Apps the user pinned to the dock ("Add to Home"), in the order they were added. */
    private final List<String> homePkgs = new ArrayList<>();
    private final List<String> menuActions = new ArrayList<>();
    private List<AppRepo.App> fullDock = new ArrayList<>();
    private List<AppRepo.App> fullGrid = new ArrayList<>();
    private boolean lpFired;
    private final Runnable longPress = this::onLongPressSelect;
    private View clearBadge;

    private Zone zone = Zone.DOCK;
    private int topIdx, dockIdx, gridIdx;
    private final RectF hl = new RectF();
    private float hlRadius, hlRadiusNow;
    private boolean hlPlaced;
    private ValueAnimator hlAnim, glowAnim;
    private int glowColor = 0;
    private int lastTitleApp = -1;

    // rotating wallpapers: Files from the photo store (see Wallpapers)
    private final List<Object> wallpapers = new ArrayList<>();
    private final ImageView[] wpViews = new ImageView[2];
    private int wpIdx, wpFront;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        applyThemePalette();
        COLS = Math.max(3, Math.min(6, getResources().getInteger(R.integer.columns)));
        int savedZone = prefs.getInt("zone", Zone.DOCK.ordinal());
        zone = savedZone >= 0 && savedZone < Zone.values().length
                ? Zone.values()[savedZone] : Zone.DOCK;
        topIdx = Math.min(prefs.getInt("top", 0), PILLS - 1);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        hiddenPkgs.addAll(prefs.getStringSet("hidden_apps", java.util.Collections.emptySet()));
        String pins = prefs.getString("home_apps", "");
        if (pins != null) for (String pkg : pins.split(",")) {
            pkg = pkg.trim();
            if (!pkg.isEmpty() && !homePkgs.contains(pkg)) homePkgs.add(pkg);
        }
        if (zone == Zone.TOP) zone = Zone.DOCK;   // come back to apps, not the buttons

        buildUi();
        setContentView(root);
        // Jarvis v3.12.0: full-screen voice overlay. Its panel sits on top of the
        // unchanged home UI; it is only shown when the mic pill is pressed.
        jarvis = new Jarvis(this, new Jarvis.Opener() {
            @Override public String openApp(String spokenName) { return openJarvisApp(spokenName); }
            @Override public void openSettings() { launch(new Intent(Settings.ACTION_SETTINGS)); }
            @Override public void adjustVolume(int direction) {
                try {
                    android.media.AudioManager am = (android.media.AudioManager)
                            getSystemService(AUDIO_SERVICE);
                    if (am == null) return;
                    if (direction > 0) am.adjustVolume(android.media.AudioManager.ADJUST_RAISE,
                            android.media.AudioManager.FLAG_SHOW_UI);
                    else if (direction < 0) am.adjustVolume(android.media.AudioManager.ADJUST_LOWER,
                            android.media.AudioManager.FLAG_SHOW_UI);
                    else am.setStreamMute(android.media.AudioManager.STREAM_MUSIC,
                            !am.isStreamMute(android.media.AudioManager.STREAM_MUSIC));
                } catch (Exception ignored) { }
            }
        });
        root.addView(jarvis.getPanel(), new FrameLayout.LayoutParams(-1, -1));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        time.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        time.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(statusReceiver, time);

        IntentFilter pk = new IntentFilter();
        pk.addAction(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pk.addDataScheme("package");
        registerReceiver(packageReceiver, pk);

        loadApps();

        Wallpapers.setListener(this);
        startForegroundService(new Intent(this, PhoneService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateWifi();
        if (inputs.isOpen()) inputs.close();
        if (powerMenu.isOpen()) powerMenu.close();
        if (appMenu.isOpen()) { appMenu.close(); menuMode = MENU_NONE; }
        scheduleMinute();
        scheduleWallpaper();
        if (hasSocial && System.currentTimeMillis() - prefs.getLong("counts_at", 0) > SUBS_REFRESH_MS) {
            refreshSubs.run();
        } else if (hasSocial) {
            main.postDelayed(refreshSubs, SUBS_REFRESH_MS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Jarvis.REQ_MIC && jarvis != null) {
            jarvis.onMicPermissionResult(grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
        }
    }

    @Override
    protected void onPause() {
        // nothing ticks (and no wallpaper is decoded) while an app is in front
        if (jarvis != null) jarvis.pauseListening();
        main.removeCallbacks(minuteTick);
        main.removeCallbacks(nextWallpaper);
        main.removeCallbacks(refreshSubs);
        main.removeCallbacks(longPress);
        super.onPause();
    }

    /** Some TV firmwares don't deliver ACTION_TIME_TICK, so the clock runs on our own timer. */
    private final Runnable minuteTick = () -> {
        updateClock();
        scheduleMinute();
    };

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        long now = System.currentTimeMillis();
        main.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and return to the dock.
        if (inputs.isOpen()) inputs.close();
        if (powerMenu.isOpen()) powerMenu.close();
        if (appMenu.isOpen()) { appMenu.close(); menuMode = MENU_NONE; }
        if (zone == Zone.GRID && hasWindowFocus()) {
            zone = Zone.DOCK;
            applyFocus(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (jarvis != null) jarvis.destroy();
        unregisterReceiver(statusReceiver);
        unregisterReceiver(packageReceiver);
        Wallpapers.setListener(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) updateWifi();
            else updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private void loadApps() {
        io.execute(() -> {
            AppRepo.Result r = AppRepo.load(getApplicationContext());
            main.post(() -> setApps(r));
        });
    }

    private void setApps(AppRepo.Result r) {
        fullDock = r.dock;
        fullGrid = r.grid;
        refreshAppLists();
    }

    /** Rebuilds the visible dock/grid from the full lists, minus the user-hidden apps. */
    private void refreshAppLists() {
        List<AppRepo.App> dock = new ArrayList<>();
        Set<String> inDock = new HashSet<>();
        for (AppRepo.App a : fullDock) if (!hiddenPkgs.contains(a.pkg)) {
            dock.add(a);
            inDock.add(a.pkg);
        }
        // user-pinned apps join the dock in the order they were added
        for (String pkg : homePkgs) {
            if (hiddenPkgs.contains(pkg) || inDock.contains(pkg)) continue;
            for (AppRepo.App a : fullGrid) if (a.pkg.equals(pkg)) {
                dock.add(a);
                inDock.add(pkg);
                break;
            }
        }
        List<AppRepo.App> grid = new ArrayList<>();
        for (AppRepo.App a : fullGrid) {
            if (!hiddenPkgs.contains(a.pkg) && !inDock.contains(a.pkg)) grid.add(a);
        }
        dockAdapter.setApps(dock);
        gridAdapter.setApps(grid);
        int rows = (grid.size() + COLS - 1) / COLS;
        ViewGroup.LayoutParams glp = gridRv.getLayoutParams();
        glp.height = Math.max(rows, 1) * rowH;
        gridRv.setLayoutParams(glp);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = gridTop + Math.max(rows, 1) * rowH + Ui.dp(this, 40);
        content.setLayoutParams(clp);
        lastTitleApp = -1;
        clampIndices();
        // Wait for the RecyclerViews to lay out before placing the highlight.
        content.post(() -> content.post(() -> applyFocus(hlPlaced)));
    }

    private void clampIndices() {
        int nd = dockAdapter.getItemCount(), ng = gridAdapter.getItemCount();
        dockIdx = Math.max(0, Math.min(dockIdx, nd - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, ng - 1));
        if (zone == Zone.GRID && ng == 0) zone = Zone.DOCK;
        if (zone == Zone.DOCK && nd == 0) zone = ng > 0 ? Zone.GRID : Zone.TOP;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // Single dark palette — the dark/light mode switch was removed.
    private void applyThemePalette() {
        colText = 0xFFFFFFFF;
        colTextDim = 0xD9FFFFFF;
        colHeader = 0xE6FFFFFF;
        colCardLabel = 0xEEFFFFFF;
        colPillFill = 0x2BFFFFFF;
        colPillStroke = 0x3DFFFFFF;
        colPillText = 0xFFFFFFFF;
        colBand = 0x33000000;
        colBandStroke = 0x1FFFFFFF;
        scrimColors = new int[]{0x4D000000, 0x59000000, 0xD9000000};
    }

    private void buildUi() {
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        side = dp(40);
        // cells shrink to fit COLS per row; cards keep the 16:9 TV banner shape
        int gap = dp(24);
        int dockCell = (W - 2 * side - 2 * dp(16)) / COLS, dockCardW = dockCell - gap;
        int dockCardH = dockCardW * 9 / 16;
        int gridCell = (W - 2 * side + gap) / COLS;
        int gridCardW = gridCell - gap;
        int gridCardH = gridCardW * 9 / 16;
        int bandH = dockCardH + dp(28), bandBottom = dp(26);
        bandTop = H - bandBottom - bandH;
        rowH = gridCardH + dp(42);
        gridTop = H + dp(56);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        boolean useWallpaper = getResources().getBoolean(R.bool.use_wallpaper);
        if (useWallpaper) {
            findWallpapers();
            for (int i = 0; i < 2; i++) {
                wpViews[i] = new ImageView(this);
                wpViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                wpViews[i].setAlpha(i == 0 ? 1f : 0f);
                root.addView(wpViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            if (!wallpapers.isEmpty()) {
                wpIdx = prefs.getInt("wallpaper", 0) % wallpapers.size();
                wpViews[0].setImageBitmap(loadWallpaper(wallpapers.get(wpIdx)));
            }
        }
        // scrim: keeps text and tiles readable over the wallpaper
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                scrimColors));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, H * 2));

        // soft halo behind the focused dock tile; follows its centre in applyFocus
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // circle fades out before the view edges; scaleY flattens it into an ellipse
        glowBg.setGradientRadius(dp(240));
        glowBg.setGradientCenter(0.4f, 0.5f);
        glowBg.setColors(new int[]{0, 0});
        glow.setBackground(glowBg);
        glow.setScaleY(0.45f);
        int glowCx = side + dp(110), glowCy = bandTop - dp(44);
        content.addView(glow, at(dp(900), dp(600), glowCx - dp(360), glowCy - dp(300)));

        // dock band
        View band = new View(this);
        band.setBackground(Ui.rounded(colBand, dp(24), dp(1), colBandStroke));
        content.addView(band, at(W - 2 * side, bandH, side, bandTop));

        // the single glass highlight (below tiles and pills)
        highlight = new GlassHighlight(this);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        // greeting, clock, date - an editorial stack: small tracked-out caps for the
        // greeting, one big bold clock between them, bold date, slim rule as divider
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greeting = Ui.text(this, 600, 13, colTextDim);
        greeting.setLetterSpacing(0.24f);
        clock = Ui.text(this, 700, 96, colText);
        clock.setLetterSpacing(-0.015f);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(dp(10), 0, dp(4), 0x73000000);
        date = Ui.text(this, 700, 14, colHeader);
        date.setLetterSpacing(0.18f);
        tl.addView(greeting);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = -dp(10);
        clp.leftMargin = -dp(4);
        tl.addView(clock, clp);
        View rule = new View(this);
        rule.setBackground(Ui.rounded(0x8CFFFFFF, dp(2), 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(38), dp(3));
        rlp.topMargin = dp(12);
        tl.addView(rule, rlp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(10);
        tl.addView(date, dlp);
        content.addView(tl, at(W / 2, -2, side, dp(30)));

        // pills: Inputs, Wi-Fi, RAM, Power, Settings, Hidden, Jarvis mic
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[0] = pill(R.drawable.ic_input, "Inputs");
        pills[1] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((ViewGroup) pills[1]).getChildAt(1);
        pills[2] = pill(R.drawable.ic_clear, "RAM …");
        ramText = (TextView) ((ViewGroup) pills[2]).getChildAt(1);
        pills[3] = pill(R.drawable.ic_power, null);
        pills[4] = pill(R.drawable.ic_settings, null);
        pills[5] = pill(R.drawable.ic_hidden, null);
        pills[6] = pill(R.drawable.ic_mic, null);
        for (int i = 0; i < PILLS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) lp.leftMargin = dp(10);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(30);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // follower counters, right-aligned under the pills (display only, never focused)
        socialIds[0] = getString(R.string.youtube_channel).trim();
        socialIds[1] = getString(R.string.instagram_user).trim();
        socialIds[2] = getString(R.string.facebook_page).trim();
        int[] logos = {R.drawable.ic_youtube, R.drawable.ic_instagram, R.drawable.ic_facebook};
        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.VERTICAL);
        counters.setGravity(Gravity.END);
        for (int i = 0; i < 3; i++) {
            if (socialIds[i].isEmpty()) continue;
            hasSocial = true;
            LinearLayout sp = new LinearLayout(this);
            sp.setOrientation(LinearLayout.HORIZONTAL);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            sp.setBackground(Ui.rounded(0x33000000, dp(16), dp(1), 0x1FFFFFFF));
            sp.setPadding(dp(12), 0, dp(14), 0);
            ImageView logo = new ImageView(this);
            logo.setImageResource(logos[i]);
            sp.addView(logo, new LinearLayout.LayoutParams(dp(20), dp(20)));
            socialTexts[i] = Ui.text(this, 500, 14, 0xFFFFFFFF);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(8);
            sp.addView(socialTexts[i], tlp);
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, dp(32));
            plp2.topMargin = dp(8);
            counters.addView(sp, plp2);
            socialPills[i] = sp;
            showCount(i, prefs.getString("count_" + SOCIAL_KEYS[i], null));
        }
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp2.topMargin = dp(30 + 36 + 8);
        clp2.rightMargin = side;
        content.addView(counters, clp2);

        // dock
        dockAdapter = new TileAdapter(dockCell, dockCardW, dockCardH, 0, false, colCardLabel);
        dockRv = recycler(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false), dockAdapter);
        content.addView(dockRv, at(dockCell * COLS, dockCardH, side + dp(28) - (dockCell - dockCardW) / 2,
                bandTop + (bandH - dockCardH) / 2));


        // grid
        TextView appsHeader = Ui.text(this, 600, 20, colHeader);
        appsHeader.setText("All Apps");
        appsHeader.setLetterSpacing(0.04f);
        content.addView(appsHeader, at(-2, -2, side + dp(2), gridTop - dp(54)));
        gridAdapter = new TileAdapter(gridCell, gridCardW, gridCardH, rowH - gridCardH, true, colCardLabel);
        gridRv = recycler(new GridLayoutManager(this, COLS), gridAdapter);
        content.addView(gridRv, at(gridCell * COLS, rowH, side - (gridCell - gridCardW) / 2, gridTop));

        // focused dock tile's name floating above its card (hidden on pills row and grid)
        hoverName = Ui.text(this, 500, 28, colText);
        hoverName.setLetterSpacing(-0.01f);
        hoverName.setGravity(Gravity.CENTER);
        hoverName.setShadowLayer(dp(8), 0, dp(3), 0x80000000);
        hoverName.setFocusable(false);
        hoverName.setAlpha(0f);
        content.addView(hoverName, new FrameLayout.LayoutParams(-2, -2));

        inputs = new InputsMenu(this);
        root.addView(inputs.panel);
        powerMenu = new PowerMenu(this);
        root.addView(powerMenu.panel);
        appMenu = new AppMenu(this);
        root.addView(appMenu.panel);

        // "Recents cleared" confirmation badge: centred glass card, pops in after Clear All
        FrameLayout badge = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.rounded(0xEB1C1C1E, dp(20), dp(1), 0x24FFFFFF));
        card.setPadding(dp(30), dp(20), dp(30), dp(20));
        TextView tick = Ui.text(this, 600, 40, 0xFF4CD964);
        tick.setText("✓");
        tick.setGravity(Gravity.CENTER);
        card.addView(tick);
        TextView msg = Ui.text(this, 500, 16, 0xFFFFFFFF);
        msg.setText("Recents cleared");
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = dp(6);
        card.addView(msg, mlp);
        badge.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        badge.setVisibility(View.GONE);
        clearBadge = badge;
        root.addView(badge, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Pops the "Recents cleared" badge in with a spring, then fades it away. */
    private void showCleared() {
        clearBadge.animate().cancel();
        clearBadge.setVisibility(View.VISIBLE);
        clearBadge.setAlpha(0f);
        clearBadge.setScaleX(0.85f);
        clearBadge.setScaleY(0.85f);
        clearBadge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new OvershootInterpolator()).start();
        main.postDelayed(() -> clearBadge.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> clearBadge.setVisibility(View.GONE)).start(), 1300);
    }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private RecyclerView recycler(RecyclerView.LayoutManager lm, RecyclerView.Adapter<?> a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        rv.setItemAnimator(null);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
        return rv;
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.rounded(colPillFill, dp(20), dp(1), colPillStroke));
        int padH = label == null ? dp(9) : dp(14);
        p.setPadding(padH, 0, padH, 0);
        p.setAlpha(0.8f);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        if (label != null) {
            TextView t = Ui.text(this, 400, 14, colPillText);
            t.setText(label);
            t.setMaxWidth(dp(170));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            p.addView(t, lp);
        }
        return p;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String part = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
        String name = getString(R.string.greeting_name).trim();
        greeting.setText((name.isEmpty() ? part : part + ", " + name)
                .toUpperCase(java.util.Locale.ROOT));
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now).toString()
                .toUpperCase(java.util.Locale.ROOT));
        updateRam();
    }

    /** RAM usage on the top pill, refreshed with the clock. */
    private void updateRam() {
        if (ramText != null) {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                int used = mi.totalMem > 0 ? (int) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem) : 0;
                ramText.setText("RAM " + used + "%");
            }
        }
    }

    // ---------------------------------------------------------------- wallpapers

    private boolean findWallpapers() {
        // bundled wallpapers are copied into the photo store on first run (see Wallpapers)
        wallpapers.clear();
        wallpapers.addAll(Wallpapers.userPhotos(this));
        return !wallpapers.isEmpty();
    }

    /** Decoded at screen size in RGB_565: about 1.8 MB each on a 720p TV. */
    private android.graphics.Bitmap loadWallpaper(Object src) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(src, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return decode(src, o);
    }

    private android.graphics.Bitmap decode(Object src, android.graphics.BitmapFactory.Options o) {
        if (src instanceof Integer) return android.graphics.BitmapFactory.decodeResource(getResources(), (Integer) src, o);
        return android.graphics.BitmapFactory.decodeFile(((java.io.File) src).getPath(), o);
    }

    /** The phone page added, replaced, deleted or asked to show a photo: update the rotation now. */
    @Override
    public void onWallpapersChanged(java.io.File added) {
        if (wpViews[0] == null) return;
        Object current = wallpapers.isEmpty() ? null : wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
        findWallpapers();
        if (wallpapers.isEmpty()) {
            for (ImageView v : wpViews) v.setImageDrawable(null);
            return;
        }
        int at = added != null ? wallpapers.indexOf(added) : wallpapers.indexOf(current);
        if (at < 0) {
            // the photo on screen was deleted: move on to the next one
            wpIdx = Math.max(0, wpIdx - 1) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else if (added != null) {
            wpIdx = (at - 1 + wallpapers.size()) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else {
            wpIdx = at;
        }
    }

    // ---------------------------------------------------------------- follower counters

    private final Runnable refreshSubs = () -> {
        main.removeCallbacks(this.refreshSubs);
        String[] ids = socialIds.clone();
        io.execute(() -> {
            String[] counts = {
                    ids[0].isEmpty() ? null : SocialCount.youtube(ids[0]),
                    ids[1].isEmpty() ? null : SocialCount.instagram(ids[1]),
                    ids[2].isEmpty() ? null : SocialCount.facebook(ids[2]),
            };
            main.post(() -> {
                boolean allOk = true;
                SharedPreferences.Editor e = prefs.edit();
                for (int i = 0; i < 3; i++) {
                    if (ids[i].isEmpty()) continue;
                    if (counts[i] == null) {
                        allOk = false;   // keep showing the last known number
                        continue;
                    }
                    e.putString("count_" + SOCIAL_KEYS[i], counts[i]);
                    showCount(i, counts[i]);
                }
                if (allOk) e.putLong("counts_at", System.currentTimeMillis());
                e.apply();
                // retry sooner after a failure (e.g. Wi-Fi still connecting after boot)
                if (hasWindowFocus()) main.postDelayed(this.refreshSubs, allOk ? SUBS_REFRESH_MS : 5 * 60_000L);
            });
        });
    };

    private void showCount(int i, String count) {
        if (socialPills[i] == null) return;
        socialPills[i].setVisibility(count == null ? View.GONE : View.VISIBLE);
        if (count != null) socialTexts[i].setText(count + " " + SOCIAL_UNITS[i]);
    }

    private void scheduleWallpaper() {
        main.removeCallbacks(nextWallpaper);
        if (wallpapers.size() < 2 || wpViews[0] == null) return;
        int secs = Math.max(5, getResources().getInteger(R.integer.wallpaper_seconds));
        main.postDelayed(nextWallpaper, secs * 1000L);
    }

    /** Fades the next wallpaper in over the current one, then frees the old bitmap. */
    private final Runnable nextWallpaper = () -> {
        if (wallpapers.isEmpty()) return;
        wpIdx = (wpIdx + 1) % wallpapers.size();
        prefs.edit().putInt("wallpaper", wpIdx).apply();
        Object src = wallpapers.get(wpIdx);
        io.execute(() -> {
            android.graphics.Bitmap bmp = loadWallpaper(src);
            if (bmp == null) return;
            main.post(() -> {
                ImageView front = wpViews[wpFront], back = wpViews[1 - wpFront];
                back.setImageBitmap(bmp);
                back.animate().alpha(1f).setDuration(2000).start();
                front.animate().alpha(0f).setDuration(2000).withEndAction(() -> front.setImageDrawable(null)).start();
                wpFront = 1 - wpFront;
                if (hasWindowFocus()) scheduleWallpaper();
            });
        });
    };

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String text = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                text = "Ethernet";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                text = "Connected";
            }
        }
        wifiText.setText(text);
    }



    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean menuOpen = inputs.isOpen() || powerMenu.isOpen() || appMenu.isOpen();
        // Jarvis overlay owns every key while it is open: OK = ask again, Back = close.
        if (jarvis != null && jarvis.isOpen()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) jarvis.close();
                else if (select && e.getRepeatCount() == 0) jarvis.listenAgain();
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            // A short OK press launches on release; a long press already opened the menu.
            if (select && !menuOpen) {
                main.removeCallbacks(longPress);
                if (!lpFired) activate();
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (inputs.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) inputs.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) inputs.move(1);
            else if (select && e.getRepeatCount() == 0) {
                Intent i = inputs.selected();
                inputs.close();
                if (i != null) launch(i);
            } else inputs.close();   // back, left, right
            return true;
        }
        if (powerMenu.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) powerMenu.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) powerMenu.move(1);
            else if (select && e.getRepeatCount() == 0) {
                String cmd = powerMenu.selectedCommand();
                powerMenu.close();
                KeyInjector.execShell(this, cmd, ok -> {
                    if (!ok) Toast.makeText(this,
                            "Power command failed - allow \"USB debugging\" on the TV if it asks",
                            Toast.LENGTH_LONG).show();
                });
            } else powerMenu.close();   // back, left, right
            return true;
        }

        if (appMenu.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) appMenu.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) appMenu.move(1);
            else if (select) {
                // The menu was opened BY a long press, so the button is still held when it
                // appears: swallow the key repeats, or they would close it instantly.
                if (e.getRepeatCount() == 0) onMenuSelect();
            }
            else { appMenu.close(); menuMode = MENU_NONE; }   // back, left, right
            return true;
        }
        if (select) {
            // Wait: a 500 ms hold opens the app menu instead of launching (see ACTION_UP).
            if (e.getRepeatCount() == 0) {
                lpFired = false;
                main.removeCallbacks(longPress);
                main.postDelayed(longPress, 500);
            }
            return true;
        }
        // arrows / back cancel a pending OK press
        main.removeCallbacks(longPress);
        lpFired = true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != Zone.DOCK && dockAdapter.getItemCount() > 0) {
                    zone = Zone.DOCK;
                    applyFocus(true);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void up() {
        if (zone == Zone.DOCK) {
            zone = Zone.TOP;
        } else if (zone == Zone.GRID) {
            if (gridIdx >= COLS) gridIdx -= COLS;
            else if (dockAdapter.getItemCount() > 0) {
                zone = Zone.DOCK;
                dockIdx = Math.min(gridIdx, dockAdapter.getItemCount() - 1);
            } else zone = Zone.TOP;
        } else return;
        applyFocus(true);
    }

    private void down() {
        int ng = gridAdapter.getItemCount();
        if (zone == Zone.TOP) {
            if (dockAdapter.getItemCount() > 0) zone = Zone.DOCK;
            else if (ng > 0) zone = Zone.GRID;
            else return;
        } else if (zone == Zone.DOCK) {
            if (ng == 0) return;
            zone = Zone.GRID;
            gridIdx = Math.min(dockIdx, ng - 1);
        } else {
            if (gridIdx + COLS < ng) gridIdx += COLS;
            else if (gridIdx / COLS < (ng - 1) / COLS) gridIdx = ng - 1;
            else return;
        }
        applyFocus(true);
    }

    private void left() {
        if (zone == Zone.TOP && topIdx > 0) topIdx--;
        else if (zone == Zone.DOCK && dockIdx > 0) dockIdx--;
        else if (zone == Zone.GRID && gridIdx % COLS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == Zone.TOP && topIdx < PILLS - 1) topIdx++;
        else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount() - 1) dockIdx++;
        else if (zone == Zone.GRID && gridIdx % COLS < COLS - 1 && gridIdx + 1 < gridAdapter.getItemCount()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void activate() {
        if (zone == Zone.TOP) {
            if (topIdx == 0) {
                int[] loc = new int[2];
                pills[0].getLocationInWindow(loc);
                inputs.open(loc[0], loc[1] + pills[0].getHeight() + dp(8));
            } else if (topIdx == 1) {
                launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else if (topIdx == 2) {
                // RAM pill: kill every background app through the TV's own ADB shell, then
                // confirm on screen with the animated badge
                KeyInjector.execShell(this, "am kill-all", ok -> {
                    if (ok) { showCleared(); updateRam(); }
                    else Toast.makeText(this,
                            "Clear failed - allow \"USB debugging\" on the TV if it asks",
                            Toast.LENGTH_LONG).show();
                });
            } else if (topIdx == 3) {
                int[] loc = new int[2];
                pills[3].getLocationInWindow(loc);
                powerMenu.open(loc[0], loc[1] + pills[3].getHeight() + dp(8));
            } else if (topIdx == 5) {
                openHiddenMenu();
            } else if (topIdx == 6) {
                if (jarvis != null) jarvis.open();
            } else {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        } else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), gridAdapter.apps.get(gridIdx)));
        }
    }

    // ---------------------------------------------------------------- hidden apps

    private AppRepo.App focusedApp() {
        if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) return dockAdapter.apps.get(dockIdx);
        if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) return gridAdapter.apps.get(gridIdx);
        return null;
    }

    /** OK held for 500 ms on a tile: open its context menu instead of launching. */
    private void onLongPressSelect() {
        // Hold OK on the mic pill: AI provider/key screen (no APK update needed).
        if (zone == Zone.TOP && topIdx == 6 && jarvis != null) {
            lpFired = true;
            jarvis.openKeySettings();
            return;
        }
        if (appMenu.isOpen()) return;
        AppRepo.App a = focusedApp();
        if (a == null) return;
        lpFired = true;
        menuApp = a;
        menuMode = MENU_APP;
        List<String> rows = new ArrayList<>();
        menuActions.clear();
        rows.add("Open");
        menuActions.add("open");
        if (zone == Zone.GRID) {
            rows.add("Add to Home");
            menuActions.add("add_home");
        } else if (zone == Zone.DOCK && homePkgs.contains(a.pkg)) {
            rows.add("Remove from Home");
            menuActions.add("remove_home");
        }
        rows.add("Hide app");
        menuActions.add("hide");
        // hl is in content coordinates; the panel sits in the window, so undo the scroll shift
        int mx = Math.min(Math.max(dp(8), (int) hl.left), W - dp(280));
        int my = Math.min((int) (hl.bottom + content.getTranslationY()) + dp(8), H - dp(160));
        appMenu.open(mx, Math.max(dp(8), my), a.label, rows);
    }

    private void onMenuSelect() {
        int idx = appMenu.selectedIndex();
        int mode = menuMode;
        appMenu.close();
        menuMode = MENU_NONE;
        if (mode == MENU_APP && menuApp != null && idx >= 0 && idx < menuActions.size()) {
            String action = menuActions.get(idx);
            if ("open".equals(action)) launch(AppRepo.launchIntent(getPackageManager(), menuApp));
            else if ("add_home".equals(action)) addToHome(menuApp);
            else if ("remove_home".equals(action)) removeFromHome(menuApp);
            else if ("hide".equals(action)) hideApp(menuApp);
        } else if (mode == MENU_HIDDEN && idx >= 0 && idx < hiddenShown.size()) {
            unhideApp(hiddenShown.get(idx));
        }
    }

    private void hideApp(AppRepo.App a) {
        hiddenPkgs.add(a.pkg);
        prefs.edit().putStringSet("hidden_apps", new HashSet<>(hiddenPkgs)).apply();
        refreshAppLists();
        Toast.makeText(this, a.label + " hidden", Toast.LENGTH_SHORT).show();
    }

    private void saveHomePkgs() {
        prefs.edit().putString("home_apps", String.join(",", homePkgs)).apply();
    }

    /** Pin an app to the dock; focus follows it so the user sees where it landed. */
    private void addToHome(AppRepo.App a) {
        if (!homePkgs.contains(a.pkg)) homePkgs.add(a.pkg);
        saveHomePkgs();
        refreshAppLists();
        for (int i = 0; i < dockAdapter.getItemCount(); i++) {
            if (dockAdapter.apps.get(i).pkg.equals(a.pkg)) {
                zone = Zone.DOCK;
                dockIdx = i;
                break;
            }
        }
        applyFocus(true);
        Toast.makeText(this, a.label + " added to Home", Toast.LENGTH_SHORT).show();
    }

    /** Unpin an app: it goes back to the All Apps grid; focus follows it there. */
    private void removeFromHome(AppRepo.App a) {
        homePkgs.remove(a.pkg);
        saveHomePkgs();
        refreshAppLists();
        for (int i = 0; i < gridAdapter.getItemCount(); i++) {
            if (gridAdapter.apps.get(i).pkg.equals(a.pkg)) {
                zone = Zone.GRID;
                gridIdx = i;
                break;
            }
        }
        applyFocus(true);
        Toast.makeText(this, a.label + " removed from Home", Toast.LENGTH_SHORT).show();
    }

    private void unhideApp(AppRepo.App a) {
        hiddenPkgs.remove(a.pkg);
        prefs.edit().putStringSet("hidden_apps", new HashSet<>(hiddenPkgs)).apply();
        refreshAppLists();
        Toast.makeText(this, a.label + " is visible again", Toast.LENGTH_SHORT).show();
    }

    /** The eye pill on top: list the hidden apps; OK on one brings it back. */
    private void openHiddenMenu() {
        hiddenShown.clear();
        List<String> labels = new ArrayList<>();
        for (AppRepo.App a : fullDock) if (hiddenPkgs.contains(a.pkg)) { hiddenShown.add(a); labels.add(a.label); }
        for (AppRepo.App a : fullGrid) if (hiddenPkgs.contains(a.pkg)) { hiddenShown.add(a); labels.add(a.label); }
        if (hiddenShown.isEmpty()) {
            Toast.makeText(this, "No hidden apps. Hold OK on any app to hide it.", Toast.LENGTH_LONG).show();
            return;
        }
        menuMode = MENU_HIDDEN;
        int[] loc = new int[2];
        pills[5].getLocationInWindow(loc);
        appMenu.open(Math.max(dp(8), loc[0] + pills[5].getWidth() - dp(272)),
                loc[1] + pills[5].getHeight() + dp(8), "Hidden apps - OK = unhide", labels);
    }

    /**
     * Jarvis app matching (v3.12.0): spoken name -> installed app, or null.
     * Aliases first (the AkhileshTV app shows as "GoogleTV", so "AkhileshTV"
     * would never match its label), then exact label, then a contains match.
     */
    private String openJarvisApp(String spoken) {
        String s = spoken.toLowerCase(java.util.Locale.ROOT).trim();
        String aliasPkg = null;
        if (s.contains("akhilesh") || s.contains("google tv") || s.contains("googletv")) aliasPkg = "com.pro.akhileshtv";
        else if (s.contains("pixel")) aliasPkg = "com.pro.pixeltv";
        else if (s.contains("album") || s.contains("photo")) aliasPkg = "com.pro.albumtv";
        else if (s.contains("promusic") || s.contains("pro music")) aliasPkg = "com.promusic.app";
        else if (s.contains("live tv")) aliasPkg = "com.nk.live_tv";
        if (aliasPkg != null) {
            for (AppRepo.App a : fullDock) if (a.pkg.equals(aliasPkg)) { launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label; }
            for (AppRepo.App a : fullGrid) if (a.pkg.equals(aliasPkg)) { launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label; }
        }
        String flat = s.replace(" ", "");
        for (AppRepo.App a : fullDock) {
            if (a.label.toLowerCase(java.util.Locale.ROOT).replace(" ", "").equals(flat)) {
                launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label;
            }
        }
        for (AppRepo.App a : fullGrid) {
            if (a.label.toLowerCase(java.util.Locale.ROOT).replace(" ", "").equals(flat)) {
                launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label;
            }
        }
        for (AppRepo.App a : fullDock) {
            String l = a.label.toLowerCase(java.util.Locale.ROOT);
            if (l.length() >= 3 && (l.contains(s) || s.contains(l))) {
                launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label;
            }
        }
        for (AppRepo.App a : fullGrid) {
            String l = a.label.toLowerCase(java.util.Locale.ROOT);
            if (l.length() >= 3 && (l.contains(s) || s.contains(l))) {
                launch(AppRepo.launchIntent(getPackageManager(), a)); return a.label;
            }
        }
        return null;
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void applyFocus(boolean animate) {
        prefs.edit().putInt("zone", zone.ordinal()).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();

        dockAdapter.setFocused(dockRv, zone == Zone.DOCK ? dockIdx : -1);
        gridAdapter.setFocused(gridRv, zone == Zone.GRID ? gridIdx : -1);
        for (int i = 0; i < PILLS; i++) {
            float a = zone == Zone.TOP && i == topIdx ? 1f : 0.8f;
            pills[i].animate().alpha(a).setDuration(150).start();
        }

        // scroll: dock/top at rest, grid rows bring the grid up one row at a time
        float shift = 0;
        if (zone == Zone.GRID) {
            int row = gridIdx / COLS;
            shift = gridTop - dp(78) + Math.max(0, row - 1) * rowH;
        }
        if (animate) content.animate().translationY(-shift).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        else content.setTranslationY(-shift);

        // ambient glow follows the dock selection
        if (dockAdapter.getItemCount() > 0 && dockIdx != lastTitleApp) {
            lastTitleApp = dockIdx;
            setGlow(dockAdapter.apps.get(dockIdx).glow, animate);
        }

        glow.animate().alpha(zone == Zone.DOCK ? 1f : 0f).setDuration(300).start();

        // The dock is a single horizontal row: when focus lands on a tile that
        // is scrolled out of the window, scroll it into view first and retry
        // placing the highlight once, after it has been laid out.
        if (zone == Zone.DOCK && dockAdapter.getItemCount() > 0
                && dockRv.findViewHolderForAdapterPosition(dockIdx) == null) {
            ((LinearLayoutManager) dockRv.getLayoutManager())
                    .scrollToPositionWithOffset(dockIdx, dp(28));
            if (!dockScrollRetry) {
                dockScrollRetry = true;
                content.post(() -> applyFocus(false));
                return;
            }
        } else {
            dockScrollRetry = false;
        }

        RectF target = targetRect();
        if (target != null) moveHighlight(target, animate && hlPlaced);
        // the glow used to sit at its creation spot above the first tile and only
        // change colour — now it centres on the focused dock tile, clear of the name text
        if (zone == Zone.DOCK && target != null) {
            float cx = (target.left + target.right) / 2f;
            float cy = (target.top + target.bottom) / 2f;
            glow.animate().translationX(cx - (side + dp(110)))
                    .translationY(cy - (bandTop - dp(44))).setDuration(300).start();
        }
        placeHoverName(target);
    }

    /** Shows the focused dock tile's name above its card; hidden for pills, grid tiles, or no target. */
    private void placeHoverName(RectF target) {
        AppRepo.App a = null;
        if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) a = dockAdapter.apps.get(dockIdx);
        if (a == null || target == null) {
            hoverName.animate().alpha(0f).setDuration(120).start();
            return;
        }
        hoverName.setText(a.label);
        int unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        hoverName.measure(unspec, unspec);
        int w = hoverName.getMeasuredWidth(), h = hoverName.getMeasuredHeight();
        float cx = (target.left + target.right) / 2f;
        float x = Math.max(side, Math.min(cx - w / 2f, W - side - w));
        float y = target.top - h - dp(10);
        if (y < 0) y = target.bottom + dp(10);
        hoverName.setX(x);
        hoverName.setY(y);
        hoverName.animate().alpha(1f).setDuration(150).start();
    }

    private RectF targetRect() {
        if (zone == Zone.TOP) {
            View p = pills[topIdx];
            float x = pillRow.getLeft() + p.getLeft(), y = pillRow.getTop() + p.getTop();
            if (p.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = p.getHeight() / 2f + o;
            return new RectF(x - o, y - o, x + p.getWidth() + o, y + p.getHeight() + o);
        }
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        float cx = rv.getLeft() + vh.itemView.getLeft() + card.getLeft() + card.getWidth() / 2f;
        float cy = rv.getTop() + vh.itemView.getTop() + card.getTop() + card.getHeight() / 2f;
        float hw = card.getWidth() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        float hh = card.getHeight() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        hlRadius = dp(24);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void moveHighlight(RectF to, boolean animate) {
        if (hlAnim != null) hlAnim.cancel();
        final RectF from = new RectF(hl);
        final float r0 = hlRadiusNow, r1 = hlRadius;
        if (!animate) {
            hl.set(to);
            placeHighlight(hl, r1);
            highlight.animate().alpha(1f).setDuration(200).start();
            hlPlaced = true;
            return;
        }
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(340);
        hlAnim.setInterpolator(new OvershootInterpolator(1.15f));
        hlAnim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            hl.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            placeHighlight(hl, r0 + (r1 - r0) * Math.min(1f, t));
        });
        hlAnim.start();
    }

    private void placeHighlight(RectF r, float radius) {
        hlRadiusNow = radius;
        highlight.set(r, radius);
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> paintGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = (c >>> 24) * 0x4A / 0xFF;
        glowBg.setColors(new int[]{(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb});
    }

}
