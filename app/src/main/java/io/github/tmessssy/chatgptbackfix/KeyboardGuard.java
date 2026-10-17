package io.github.tmessssy.chatgptbackfix;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.lang.ref.WeakReference;

/**
 * Stops ChatGPT from popping the keyboard open by itself when the app opens.
 *
 * Armed when a ChatGPT screen comes to the foreground, disarmed by the first real touch (so tapping
 * the message box opens the keyboard normally), when the screen leaves, or after a few seconds.
 * While armed:
 *  - requests to show the keyboard are blocked at the framework level ({@link Hook}), so it never flashes;
 *  - as a backstop, a keyboard the system shows anyway is hidden again.
 * Main thread only.
 */
final class KeyboardGuard {

    private static final long WINDOW_MS = 4000;
    private static final long POLL_MS = 100;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static boolean armed;
    private static long armedAt;
    private static boolean reported;

    private KeyboardGuard() {
    }

    static void arm(Activity a) {
        if (!ModuleConfig.keepKeyboardClosed()) return;
        current = new WeakReference<>(a);
        armed = true;
        armedAt = SystemClock.uptimeMillis();
        reported = false;
        HANDLER.removeCallbacks(TICK);
        HANDLER.post(TICK);
    }

    static void disarm(String why) {
        if (!armed) return;
        armed = false;
        HANDLER.removeCallbacks(TICK);
        L.d("keyboard guard off: " + why);
    }

    /** True when an automatic keyboard request should be dropped right now. */
    static boolean shouldBlock(String via) {
        if (!armed) return false;
        if (SystemClock.uptimeMillis() - armedAt > WINDOW_MS) {
            disarm("time window over");
            return false;
        }
        report("blocked " + via);
        return true;
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!armed) return;
            Activity a = current.get();
            if (a == null || a.isFinishing() || SystemClock.uptimeMillis() - armedAt > WINDOW_MS) {
                disarm("time window over");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Window w = a.getWindow();
                View decor = w == null ? null : w.peekDecorView();
                WindowInsets insets = decor == null ? null : decor.getRootWindowInsets();
                if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                    WindowInsetsController c = decor.getWindowInsetsController();
                    if (c != null) {
                        c.hide(WindowInsets.Type.ime());
                        report("hid it");
                    }
                }
            }
            HANDLER.postDelayed(this, POLL_MS);
        }
    };

    private static void report(String what) {
        if (reported) return;
        reported = true;
        L.i("keyboard: ChatGPT tried to open it on launch -> " + what);
    }
}
