package io.github.tmessssy.chatgptbackfix;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.reflect.Method;

/**
 * Android 13+ "predictive back" path.
 *
 * When an app opts into OnBackInvokedCallback (default for apps targeting Android 16), the system
 * never sends a BACK key event; it calls the top registered callback directly. To intercept that,
 * a callback is registered at PRIORITY_OVERLAY, above the app's own (PRIORITY_DEFAULT).
 *
 * It is only registered while the screen is in a state we act on (main chat screen or sidebar open),
 * so everywhere else ChatGPT's native back handling, including predictive animations, is untouched.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class BackInvokedBridge {

    private final OnBackInvokedDispatcher dispatcher;
    private final OnBackInvokedCallback callback;
    private boolean registered;

    BackInvokedBridge(Activity activity, OnBackInvokedCallback callback) {
        this.dispatcher = activity.getOnBackInvokedDispatcher();
        this.callback = callback;
    }

    void setRegistered(boolean want) {
        if (want == registered) return;
        try {
            if (want) {
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            } else {
                dispatcher.unregisterOnBackInvokedCallback(callback);
            }
            registered = want;
        } catch (Throwable t) {
            L.e((want ? "register" : "unregister") + " back callback failed", t);
        }
    }

    boolean isRegistered() {
        return registered;
    }

    /**
     * Whether this Activity receives back through OnBackInvokedCallback. If the framework check can't
     * be read, assume yes: registering is harmless when unused (the key-event path still covers it).
     */
    @SuppressLint("PrivateApi")
    static boolean isActive(Activity activity) {
        try {
            Class<?> c = Class.forName("android.window.WindowOnBackInvokedDispatcher");
            Method m = c.getDeclaredMethod("isOnBackInvokedCallbackEnabled", Context.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, activity);
        } catch (Throwable ignored) {
        }
        try {
            Method m = ApplicationInfo.class.getDeclaredMethod("isOnBackInvokedCallbackEnabled");
            m.setAccessible(true);
            return (Boolean) m.invoke(activity.getApplicationInfo());
        } catch (Throwable ignored) {
        }
        return true;
    }
}
