package io.github.tmessssy.chatgptbackfix;

import android.app.Activity;
import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point (listed in assets/xposed_init).
 *
 * Only framework methods are hooked (Activity lifecycle + dispatchKeyEvent), never ChatGPT's own
 * obfuscated classes, so ChatGPT updates don't break the hooks. All screen understanding happens in
 * {@link UiScanner} / {@link ScreenAnalyzer}; all back behavior lives in {@link BackController}.
 */
public final class Hook implements IXposedHookLoadPackage {

    static final String TARGET_PACKAGE = "com.openai.chatgpt";

    /** Main thread only. Weak keys so destroyed Activities are never kept alive. */
    private static final Map<Activity, BackController> CONTROLLERS = new WeakHashMap<>();
    private static boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName) || !lpparam.isFirstApplication) return;
        if (installed) return;
        installed = true;

        HiddenApi.relaxForThisProcess();
        ModuleConfig.init();
        L.i("v" + BuildConfig.VERSION_NAME + " loaded into " + lpparam.processName);

        try {
            installHooks();
        } catch (Throwable t) {
            L.e("failed to install hooks; module inactive", t);
        }
    }

    private static void installHooks() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Activity a = (Activity) param.thisObject;
                    controllerFor(a).onResume();
                    KeyboardGuard.arm(a);
                } catch (Throwable t) {
                    L.e("onResume", t);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    KeyboardGuard.disarm("screen paused");
                    BackController c = CONTROLLERS.get((Activity) param.thisObject);
                    if (c != null) c.onPause();
                } catch (Throwable t) {
                    L.e("onPause", t);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    BackController c = CONTROLLERS.remove((Activity) param.thisObject);
                    if (c != null) c.onDestroy();
                } catch (Throwable t) {
                    L.e("onDestroy", t);
                }
            }
        });

        // The heart of the module: android.app.Activity.onBackPressed only runs when ChatGPT itself had
        // nothing to do with Back (its own handlers run first and stop there). That is exactly the
        // "app is about to close" moment, so that is where the sidebar gets opened instead.
        XposedHelpers.findAndHookMethod(Activity.class, "onBackPressed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    BackController c = CONTROLLERS.get((Activity) param.thisObject);
                    if (c != null && c.onFallThroughBack()) param.setResult(null);
                } catch (Throwable t) {
                    L.e("fall-through back handling failed; letting ChatGPT close", t);
                }
            }
        });

        // Any real touch means the user is interacting with the open sidebar; the module forgets its state.
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                MotionEvent ev = (MotionEvent) param.args[0];
                if (ev == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                BackController c = CONTROLLERS.get((Activity) param.thisObject);
                if (c != null) c.onTouch(ev);
            }
        });

        hookKeyboardRequests();

        // Legacy back delivery. Runs before the view hierarchy sees the key. The keyboard (IME) and
        // dialogs/sheets in their own windows get Back first, so they keep working normally.
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                KeyEvent ev = (KeyEvent) param.args[0];
                if (ev == null || ev.getKeyCode() != KeyEvent.KEYCODE_BACK) return;
                try {
                    BackController c = CONTROLLERS.get((Activity) param.thisObject);
                    if (c == null) return;
                    if (ev.getAction() == KeyEvent.ACTION_UP) c.beginBackDispatch();
                    if (c.onBackKey(ev)) param.setResult(Boolean.TRUE);
                } catch (Throwable t) {
                    L.e("back key handling failed; using normal back", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                KeyEvent ev = (KeyEvent) param.args[0];
                if (ev == null || ev.getKeyCode() != KeyEvent.KEYCODE_BACK || ev.getAction() != KeyEvent.ACTION_UP) return;
                BackController c = CONTROLLERS.get((Activity) param.thisObject);
                if (c != null) c.endBackDispatch();
            }
        });

        // Safety net: if ChatGPT's own back handler closes the app directly (instead of falling through
        // to onBackPressed), catch that too. Only active while a Back press is being handled.
        hookClose("finish", null);
        hookClose("finishAffinity", null);
        hookClose("finishAndRemoveTask", null);
        hookClose("moveTaskToBack", Boolean.TRUE, boolean.class);
    }

    /**
     * Blocks ChatGPT's own "show the keyboard" requests while {@link KeyboardGuard} is armed (app just
     * opened, screen not touched yet). Two routes exist, both hooked:
     *  - InputMethodManager.showSoftInput (all Android versions)
     *  - InsetsController.show(types) with the IME bit (Android 11+, what Compose uses there)
     */
    private static void hookKeyboardRequests() {
        try {
            XposedBridge.hookAllMethods(InputMethodManager.class, "showSoftInput", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (KeyboardGuard.shouldBlock("showSoftInput")) param.setResult(Boolean.FALSE);
                }
            });
        } catch (Throwable t) {
            L.e("could not hook showSoftInput", t);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Class<?> insetsController = XposedHelpers.findClassIfExists("android.view.InsetsController", null);
            if (insetsController == null) return;
            final int ime = WindowInsets.Type.ime();
            XposedBridge.hookAllMethods(insetsController, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    int types = (Integer) param.args[0];
                    if ((types & ime) == 0 || !KeyboardGuard.shouldBlock("insets show")) return;
                    int rest = types & ~ime;  // still show anything else that was requested (e.g. status bar)
                    if (rest == 0) param.setResult(null);
                    else param.args[0] = rest;
                }
            });
        } catch (Throwable t) {
            L.e("could not hook InsetsController.show", t);
        }
    }

    private static void hookClose(String method, Object skippedResult, Class<?>... params) {
        Object[] args = new Object[params.length + 1];
        System.arraycopy(params, 0, args, 0, params.length);
        args[params.length] = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    BackController c = CONTROLLERS.get((Activity) param.thisObject);
                    if (c != null && c.onCloseRequested(method + "()")) param.setResult(skippedResult);
                } catch (Throwable t) {
                    L.e(method + " interception failed; letting it close", t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Activity.class, method, args);
        } catch (Throwable t) {
            L.e("could not hook Activity." + method, t);
        }
    }

    private static BackController controllerFor(Activity a) {
        BackController c = CONTROLLERS.get(a);
        if (c == null) {
            c = new BackController(a);
            CONTROLLERS.put(a, c);
        }
        return c;
    }
}
