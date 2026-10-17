package io.github.tmessssy.chatgptbackfix;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Logging for the hook side (ChatGPT's process). Everything lands in LSPosed Manager > Logs.
 * Do not call from SettingsActivity: XposedBridge does not exist in the module's own process.
 */
final class L {

    static final String TAG = "ChatGPTBackGestureFix";

    private L() {
    }

    static void i(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    static boolean debug() {
        return ModuleConfig.debug();
    }

    static void d(String msg) {
        if (debug()) XposedBridge.log(TAG + " [debug]: " + msg);
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + " [error]: " + msg + " -> " + t);
        if (debug()) XposedBridge.log(TAG + " [error]: " + Log.getStackTraceString(t));
    }
}
