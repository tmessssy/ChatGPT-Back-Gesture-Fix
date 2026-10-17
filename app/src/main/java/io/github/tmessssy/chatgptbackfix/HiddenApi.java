package io.github.tmessssy.chatgptbackfix;

import android.annotation.SuppressLint;
import android.view.accessibility.AccessibilityNodeInfo;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

/**
 * The only hidden framework APIs this module touches:
 *  - AccessibilityNodeInfo.getChildId(int): lets us walk Jetpack Compose's accessibility tree
 *    (Compose UIs expose their buttons/labels as virtual accessibility nodes, not real Views).
 *  - WindowOnBackInvokedDispatcher / ApplicationInfo "is predictive back enabled" checks.
 * Exemptions are scoped to those classes only, inside ChatGPT's process.
 */
final class HiddenApi {

    private static Method getChildId;
    private static boolean resolved;

    private HiddenApi() {
    }

    static void relaxForThisProcess() {
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/view/accessibility/AccessibilityNodeInfo;",
                    "Landroid/window/WindowOnBackInvokedDispatcher;",
                    "Landroid/content/pm/ApplicationInfo;",
                    "Landroid/view/InsetsController;");
        } catch (Throwable t) {
            L.e("hidden API exemption failed; will try direct lookups", t);
        }
    }

    /** True when the child-ID accessor is usable on this device. */
    static boolean canWalkVirtualTree() {
        return childIdMethod() != null;
    }

    /**
     * Raw child node ID (virtual ID in the upper 32 bits, owning View's accessibility ID in the lower 32).
     *
     * @throws ReflectiveOperationException if the accessor exists but fails
     */
    static long childNodeId(AccessibilityNodeInfo info, int index) throws ReflectiveOperationException {
        Method m = childIdMethod();
        if (m == null) throw new NoSuchMethodException("AccessibilityNodeInfo.getChildId(int)");
        Object v = m.invoke(info, index);
        return v instanceof Long ? (Long) v : -1L;
    }

    // Lint is right that a plain lookup is blocked for apps targeting API 35+ (ChatGPT does). That is
    // why relaxForThisProcess() runs first, and why HiddenApiBypass is the fallback below.
    @SuppressLint("SoonBlockedPrivateApi")
    private static synchronized Method childIdMethod() {
        if (!resolved) {
            resolved = true;
            try {
                getChildId = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
            } catch (Throwable direct) {
                try {
                    getChildId = HiddenApiBypass.getDeclaredMethod(AccessibilityNodeInfo.class, "getChildId", int.class);
                } catch (Throwable bypass) {
                    L.e("cannot read accessibility child IDs; sidebar detection will not work on this device", bypass);
                }
            }
            if (getChildId != null) {
                try {
                    getChildId.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
        }
        return getChildId;
    }
}
