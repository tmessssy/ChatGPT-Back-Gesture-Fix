package io.github.tmessssy.chatgptbackfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;

/**
 * Per-Activity back logic for ChatGPT.
 *
 * The core trick: ChatGPT closes on Back only when nothing inside the app wants that Back press.
 * In that case Android ends up in the framework's {@code Activity.onBackPressed()} (ChatGPT's own
 * back handlers run before that and never reach it). {@link Hook} intercepts exactly that call and
 * routes it to {@link #onFallThroughBack()}: "ChatGPT was about to close" becomes "open the sidebar".
 * No screen reading is needed to know WHEN to act, so it works even if the screen can't be read.
 *
 *   ChatGPT about to close        -> open the sidebar (tap the hamburger)
 *   Sidebar open + Back           -> "Close app?" prompt (or close immediately, per settings)
 *   Anything ChatGPT handles      -> untouched (sub-screens, sheets, menus)
 *
 * Back arrives one of two ways and both are covered:
 *   - Legacy KEYCODE_BACK key event (hooked in Activity.dispatchKeyEvent).
 *   - Android 13+ predictive back: a high-priority OnBackInvokedCallback ({@link BackInvokedBridge}).
 *     It hands Back to ChatGPT via onBackPressed(), which reaches the fall-through above if
 *     ChatGPT has nothing to do.
 *
 * "Sidebar open" = the module opened it and you haven't touched the screen since, or the screen
 * reader tree shows an open sidebar. Everything runs on the main thread.
 */
final class BackController {

    /** Sidebar slide-in time; before this the hamburger may still be visible. */
    private static final long OPEN_SETTLE_MS = 600;
    /** When to fingerprint the open sidebar (animation done). */
    private static final long LEARN_DELAY_MS = 750;
    /** Fallback tap point for the hamburger when the screen can't be read (dp from the top-left, below the status bar). */
    private static final float TAP_X_DP = 32f;
    private static final float TAP_Y_DP = 30f;
    private static final long TAP_UP_DELAY_MS = 50;

    private final WeakReference<Activity> activityRef;
    private final String name;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BackInvokedBridge bridge;

    private boolean announced;
    private boolean predictive;
    private boolean inBackDispatch;
    private boolean actedThisDispatch;
    private boolean exiting;
    private boolean keyConsuming;
    private boolean injectingTouch;
    private long sidebarOpenedAt;
    private AlertDialog exitDialog;

    BackController(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
        this.name = activity.getClass().getName();
        // Always register on Android 13+: if ChatGPT doesn't use predictive back, Android simply ignores
        // the callback and the key-event path handles Back instead. No guessing needed.
        BackInvokedBridge b = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b = new BackInvokedBridge(activity, this::onBackInvoked);
            predictive = BackInvokedBridge.isActive(activity);
        }
        this.bridge = b;
    }

    // ---------------------------------------------------------------- lifecycle (from Hook)

    void onResume() {
        Activity a = activityRef.get();
        if (a == null) return;
        if (bridge != null) bridge.setRegistered(true);
        if (!announced) {
            announced = true;
            L.i("watching " + name + " | back delivery: "
                    + (predictive ? "predictive callback" : "key event") + " | task root: " + a.isTaskRoot()
                    + " | ChatGPT " + appVersion(a));
        }
    }

    void onPause() {
        keyConsuming = false;
    }

    void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bridge != null) bridge.setRegistered(false);
        dismissDialog();
    }

    /**
     * Any real finger-down means the user is interacting with the screen: forget the sidebar state and
     * let the keyboard open normally from now on. The module's own synthetic tap doesn't count.
     */
    void onTouch(MotionEvent ev) {
        if (injectingTouch || ev.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        KeyboardGuard.disarm("user touched the screen");
        if (sidebarOpenedAt == 0) return;
        sidebarOpenedAt = 0;
        L.d("touch -> sidebar state cleared");
    }

    // ---------------------------------------------------------------- legacy path: KEYCODE_BACK

    /** Returns true when consumed. Only intercepts when the sidebar is open; everything else goes to ChatGPT. */
    boolean onBackKey(KeyEvent ev) {
        Activity a = activityRef.get();
        if (a == null) return false;
        switch (ev.getAction()) {
            case KeyEvent.ACTION_DOWN: {
                if (ev.getRepeatCount() > 0) return keyConsuming;
                keyConsuming = false;
                if (!ModuleConfig.enabled() || !a.isTaskRoot()) return false;
                String open = sidebarOpenReason(a, true);
                if (open == null) return false;  // ChatGPT handles it; if it would close, onFallThroughBack opens the sidebar
                L.i("back[key]: sidebar open (" + open + ") -> exit");
                keyConsuming = true;
                return true;
            }
            case KeyEvent.ACTION_UP: {
                if (!keyConsuming) return false;
                keyConsuming = false;
                if (!ev.isCanceled()) requestExit(a);
                return true;
            }
            default:
                return keyConsuming;
        }
    }

    // ---------------------------------------------------------------- predictive path

    private void onBackInvoked() {
        Activity a = activityRef.get();
        if (a == null) return;
        try {
            if (isImeVisible(a)) {
                // Our callback outranks the keyboard's, so do what the keyboard would have done.
                hideIme(a);
                return;
            }
            if (ModuleConfig.enabled() && a.isTaskRoot()) {
                String open = sidebarOpenReason(a, true);
                if (open != null) {
                    L.i("back[predictive]: sidebar open (" + open + ") -> exit");
                    requestExit(a);
                    return;
                }
            }
            // Hand Back to ChatGPT. If ChatGPT has nothing to do with it, this lands in
            // Activity.onBackPressed -> onFallThroughBack(), which opens the sidebar.
            beginBackDispatch();
            try {
                passThrough(a);
            } finally {
                endBackDispatch();
            }
        } catch (Throwable t) {
            L.e("back handling failed; using normal back", t);
            passThrough(a);
        }
    }

    // ---------------------------------------------------------------- "ChatGPT closes itself during Back"

    void beginBackDispatch() {
        inBackDispatch = true;
        actedThisDispatch = false;
    }

    void endBackDispatch() {
        inBackDispatch = false;
    }

    /**
     * Called when the Activity tries to finish / move to back. Only acts while a Back press is being
     * handled (so normal closes, like signing out, are untouched) and never for the module's own exit.
     */
    boolean onCloseRequested(String how) {
        if (!inBackDispatch || exiting) return false;
        if (actedThisDispatch) return true;  // this Back press was already turned into a sidebar action
        L.i("back: ChatGPT tried to close itself via " + how);
        return onFallThroughBack();
    }

    // ---------------------------------------------------------------- the fall-through: ChatGPT is about to close

    /** Called from the hook on android.app.Activity.onBackPressed. Returns true to stop the close. */
    boolean onFallThroughBack() {
        Activity a = activityRef.get();
        if (a == null || !ModuleConfig.enabled()) return false;
        if (!a.isTaskRoot()) {
            L.d("back fell through on non-root " + name + "; leaving it");
            return false;
        }
        if (exitDialog != null && exitDialog.isShowing()) return true;

        String open = sidebarOpenReason(a, false);
        if (open != null) {
            L.i("back: sidebar open (" + open + ") -> exit");
            actedThisDispatch = true;
            requestExit(a);
            return true;
        }
        if (openSidebar(a)) {
            actedThisDispatch = true;
            return true;
        }
        L.i("back: ChatGPT is closing and the sidebar could not be opened; letting it close");
        return false;
    }

    // ---------------------------------------------------------------- actions

    private boolean openSidebar(Activity a) {
        Scan s = scan(a, true);
        UiNode target = null;
        if (s != null) target = s.result.menuButton != null ? s.result.menuButton : s.result.cornerButton;

        String how = null;
        if (target != null && target.click()) {
            how = "clicked " + target;
        } else {
            String at = tapTopLeft(a);
            if (at != null) how = "tapped hamburger area " + at + (s == null ? " (screen not readable)" : " (no button found)");
        }
        if (how == null) return false;

        sidebarOpenedAt = SystemClock.uptimeMillis();
        final Set<String> before = s != null ? s.result.labels : Collections.emptySet();
        handler.postDelayed(() -> learnAfterOpen(before), LEARN_DELAY_MS);
        L.i("back: ChatGPT would have closed -> opened sidebar | " + how
                + " | readable nodes: " + (s == null ? 0 : s.snapshot.nodes.size()));
        return true;
    }

    /** Synthetic finger tap where ChatGPT's hamburger sits (top-left, just under the status bar). */
    private String tapTopLeft(Activity a) {
        Window w = a.getWindow();
        final View decor = w == null ? null : w.peekDecorView();
        if (decor == null || !decor.isAttachedToWindow() || decor.getWidth() == 0) return null;
        float density = decor.getResources().getDisplayMetrics().density;
        final float x = TAP_X_DP * density;
        final float y = topInset(decor) + TAP_Y_DP * density;
        final long downTime = SystemClock.uptimeMillis();
        dispatchTouch(decor, downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        handler.postDelayed(() -> dispatchTouch(decor, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y),
                TAP_UP_DELAY_MS);
        return "(" + Math.round(x) + ", " + Math.round(y) + ")px";
    }

    private void dispatchTouch(View decor, long downTime, long eventTime, int action, float x, float y) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectingTouch = true;
        try {
            decor.dispatchTouchEvent(ev);
        } catch (Throwable t) {
            L.e("synthetic tap failed", t);
        } finally {
            injectingTouch = false;
            ev.recycle();
        }
    }

    private void learnAfterOpen(Set<String> before) {
        Activity a = activityRef.get();
        if (a == null || a.isFinishing() || before.isEmpty()) return;
        Scan s = scan(a, false);
        if (s == null) return;
        int fresh = ScreenAnalyzer.learnSignature(before, s.result.labels);
        L.d("sidebar fingerprint: " + fresh + " new labels; stored=" + ScreenAnalyzer.signature());
    }

    private void requestExit(Activity a) {
        if (!ModuleConfig.confirmExit()) {
            exitApp(a);
            return;
        }
        if (exitDialog != null && exitDialog.isShowing()) return;
        if (a.isFinishing() || a.isDestroyed()) return;

        int theme = isNight(a)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(a, theme)
                .setTitle("Close app?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Exit App", (di, which) -> exitApp(a))
                .create();
        dialog.setOnDismissListener(di -> {
            if (exitDialog == di) exitDialog = null;
        });
        exitDialog = dialog;
        dialog.show();
    }

    private void exitApp(Activity a) {
        exiting = true;
        sidebarOpenedAt = 0;
        dismissDialog();
        L.i("closing ChatGPT");
        a.finishAffinity();
    }

    private void dismissDialog() {
        if (exitDialog == null) return;
        try {
            exitDialog.dismiss();
        } catch (Throwable ignored) {
        }
        exitDialog = null;
    }

    @SuppressWarnings("deprecation")
    private static void passThrough(Activity a) {
        try {
            a.onBackPressed();
        } catch (Throwable t) {
            L.e("normal back failed", t);
        }
    }

    // ---------------------------------------------------------------- sidebar state

    /** Non-null (a reason) when the sidebar is open. */
    private String sidebarOpenReason(Activity a, boolean userBack) {
        Scan s = scan(a, userBack);
        if (s != null && s.result.sidebarOpenReason != null) return s.result.sidebarOpenReason;
        if (sidebarOpenedAt == 0) return null;
        long since = SystemClock.uptimeMillis() - sidebarOpenedAt;
        if (since >= OPEN_SETTLE_MS && s != null && s.result.menuButton != null) {
            sidebarOpenedAt = 0;  // the hamburger is plainly visible again, so the sidebar is closed
            L.d("hamburger visible again -> sidebar state cleared");
            return null;
        }
        return "opened by Back " + since + " ms ago, screen not touched since";
    }

    // ---------------------------------------------------------------- screen reading (optional extra signal)

    private static final class Scan {
        final UiScanner.Snapshot snapshot;
        final ScreenAnalyzer.Result result;

        Scan(UiScanner.Snapshot snapshot, ScreenAnalyzer.Result result) {
            this.snapshot = snapshot;
            this.result = result;
        }
    }

    /** Null when the screen can't be read; callers then rely on the module's own state. */
    private Scan scan(Activity a, boolean logIt) {
        try {
            UiScanner.Snapshot snap = UiScanner.scan(a);
            if (snap == null || snap.nodes.isEmpty()) {
                if (logIt) L.d("screen not readable (" + (snap == null ? "no window" : "0 nodes") + ")");
                return null;
            }
            ScreenAnalyzer.Result r = ScreenAnalyzer.analyze(snap, ModuleConfig.extraLabels());
            if (logIt && L.debug()) dump(snap, r);
            return new Scan(snap, r);
        } catch (Throwable t) {
            L.e("screen scan failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String appVersion(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int topInset(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private static boolean isImeVisible(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        View decor = a.getWindow() == null ? null : a.getWindow().peekDecorView();
        WindowInsets insets = decor == null ? null : decor.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private static void hideIme(Activity a) {
        View decor = a.getWindow() == null ? null : a.getWindow().peekDecorView();
        if (decor == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.ime());
                return;
            }
        }
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
    }

    private static boolean isNight(Activity a) {
        int mode = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void dump(UiScanner.Snapshot snap, ScreenAnalyzer.Result r) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("screen of ").append(name);
        sb.append("\n  window=").append(snap.window.toShortString())
                .append(" nodes=").append(snap.nodes.size()).append(snap.truncated ? " (truncated)" : "")
                .append(" composeHosts=").append(snap.composeHosts);
        sb.append("\n  sidebarButton=").append(r.menuButton);
        sb.append("\n  cornerButton=").append(r.cornerButton);
        sb.append("\n  sidebarOpen=").append(r.sidebarOpenReason)
                .append(" | fingerprint ").append(r.signatureHits).append('/').append(ScreenAnalyzer.signature().size())
                .append(" | moduleState=").append(sidebarOpenedAt == 0 ? "closed" : "open");
        sb.append("\n  top-left clickables (").append(r.topLeftClickables.size()).append("):");
        for (UiNode n : r.topLeftClickables) sb.append("\n    ").append(n);
        sb.append("\n  visible labels (first 60):");
        int i = 0;
        for (String label : r.labels) {
            if (i++ >= 60) break;
            sb.append("\n    ").append(label);
        }
        L.d(sb.toString());
    }
}
