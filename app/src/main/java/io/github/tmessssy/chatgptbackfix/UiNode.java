package io.github.tmessssy.chatgptbackfix;

import android.graphics.Rect;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import java.util.List;

/**
 * One on-screen element: either a Jetpack Compose virtual node (read through the accessibility
 * provider) or a classic View. Holds just enough to match labels, check position, and click it.
 */
final class UiNode {

    /** Human-readable label (content description and/or text) for logs. */
    final String display;
    /** Lowercased, punctuation-free label padded with spaces, for whole-word matching. */
    final String match;
    /** Lowercased label used for the learned sidebar signature. */
    final String key;
    final String paneTitle;
    final Rect bounds;
    final boolean visible;
    final boolean clickable;
    final boolean enabled;
    final boolean dismissible;

    private final View host;
    private final AccessibilityNodeProvider provider;
    private final int virtualId;

    private UiNode(String display, CharSequence paneTitle, Rect bounds, boolean visible, boolean clickable,
                   boolean enabled, boolean dismissible, View host, AccessibilityNodeProvider provider, int virtualId) {
        this.display = display;
        String m = Text.forMatching(display);
        this.match = Text.padded(m);
        this.key = m;
        this.paneTitle = Text.padded(Text.forMatching(paneTitle));
        this.bounds = bounds;
        this.visible = visible;
        this.clickable = clickable;
        this.enabled = enabled;
        this.dismissible = dismissible;
        this.host = host;
        this.provider = provider;
        this.virtualId = virtualId;
    }

    static UiNode fromVirtual(View host, AccessibilityNodeProvider provider, int virtualId, AccessibilityNodeInfo info) {
        Rect r = new Rect();
        info.getBoundsInScreen(r);
        List<AccessibilityAction> actions = info.getActionList();
        boolean clickable = info.isClickable() || hasAction(actions, AccessibilityNodeInfo.ACTION_CLICK);
        boolean dismissible = info.isDismissable() || hasAction(actions, AccessibilityNodeInfo.ACTION_DISMISS);
        return new UiNode(
                Text.display(info.getContentDescription(), info.getText()),
                info.getPaneTitle(),
                r,
                info.isVisibleToUser(),
                clickable,
                info.isEnabled(),
                dismissible,
                host, provider, virtualId);
    }

    static UiNode fromView(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        CharSequence text = v instanceof TextView ? ((TextView) v).getText() : null;
        boolean visible = v.isShown() && v.getGlobalVisibleRect(new Rect());
        return new UiNode(
                Text.display(v.getContentDescription(), text),
                v.getAccessibilityPaneTitle(),
                r,
                visible,
                v.isClickable(),
                v.isEnabled(),
                false,
                v, null, View.NO_ID);
    }

    static boolean isInterestingView(View v) {
        if (v.isClickable()) return true;
        if (!TextUtils.isEmpty(v.getContentDescription())) return true;
        return v instanceof TextView && !TextUtils.isEmpty(((TextView) v).getText());
    }

    boolean hasLabel() {
        return !key.isEmpty();
    }

    /** Clicks the element exactly like TalkBack would (Compose) or like a tap (View). */
    boolean click() {
        try {
            if (provider != null) {
                return provider.performAction(virtualId, AccessibilityNodeInfo.ACTION_CLICK, null);
            }
            return host.performClick();
        } catch (Throwable t) {
            L.e("click failed on " + this, t);
            return false;
        }
    }

    private static boolean hasAction(List<AccessibilityAction> actions, int id) {
        if (actions == null) return false;
        for (int i = 0, n = actions.size(); i < n; i++) {
            if (actions.get(i).getId() == id) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(96);
        sb.append('"').append(display).append('"');
        sb.append(' ').append(bounds.toShortString());
        if (clickable) sb.append(" clickable");
        if (!enabled) sb.append(" disabled");
        if (!visible) sb.append(" hidden");
        if (dismissible) sb.append(" dismissible");
        if (!paneTitle.isEmpty()) sb.append(" pane=").append(paneTitle.trim());
        sb.append(provider != null ? " [compose#" + virtualId + "]" : " [" + host.getClass().getSimpleName() + "]");
        return sb.toString();
    }
}
