package io.github.tmessssy.chatgptbackfix;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.webkit.WebView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Takes a lightweight snapshot of what is on screen in an Activity's main window.
 *
 * ChatGPT's UI is Jetpack Compose, which draws everything inside one View. Its buttons and labels
 * are only visible through that View's AccessibilityNodeProvider (the same tree TalkBack reads), so
 * that is what gets walked. Compose only reports "uncovered" nodes there, which is exactly what we
 * want: things hidden behind an open sidebar or scrim are left out automatically.
 *
 * No obfuscated ChatGPT classes are referenced, so app updates that reshuffle internals do not break it.
 */
final class UiScanner {

    static final int MAX_NODES = 1500;

    private static final int UNDEFINED_ITEM_ID = Integer.MAX_VALUE;
    private static boolean warnedNoChildIds;

    private UiScanner() {
    }

    static final class Snapshot {
        final Rect window;
        final float density;
        final List<UiNode> nodes;
        final int composeHosts;
        final boolean truncated;

        Snapshot(Rect window, float density, List<UiNode> nodes, int composeHosts, boolean truncated) {
            this.window = window;
            this.density = density;
            this.nodes = nodes;
            this.composeHosts = composeHosts;
            this.truncated = truncated;
        }
    }

    /** Returns null when the window is not laid out yet. Must be called on the main thread. */
    static Snapshot scan(Activity activity) {
        Window w = activity.getWindow();
        View decor = w == null ? null : w.peekDecorView();
        if (decor == null || !decor.isAttachedToWindow() || decor.getWidth() == 0 || decor.getHeight() == 0) {
            return null;
        }
        int[] loc = new int[2];
        decor.getLocationOnScreen(loc);
        Rect window = new Rect(loc[0], loc[1], loc[0] + decor.getWidth(), loc[1] + decor.getHeight());

        List<UiNode> nodes = new ArrayList<>(128);
        int hosts = 0;
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(decor);
        while (!stack.isEmpty() && nodes.size() < MAX_NODES) {
            View v = stack.pop();
            if (v.getVisibility() != View.VISIBLE) continue;
            // WebView's provider spins up Chromium's renderer accessibility, which is expensive and sticky. Skip it.
            if (v instanceof WebView) continue;

            AccessibilityNodeProvider provider = null;
            try {
                provider = v.getAccessibilityNodeProvider();
            } catch (Throwable ignored) {
            }
            if (provider != null) {
                hosts++;
                collectVirtual(v, provider, nodes);
            } else if (UiNode.isInterestingView(v)) {
                nodes.add(UiNode.fromView(v));
            }

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) stack.push(g.getChildAt(i));
            }
        }
        return new Snapshot(window, decor.getResources().getDisplayMetrics().density, nodes, hosts,
                nodes.size() >= MAX_NODES);
    }

    private static void collectVirtual(View host, AccessibilityNodeProvider provider, List<UiNode> out) {
        if (!HiddenApi.canWalkVirtualTree()) {
            if (!warnedNoChildIds) {
                warnedNoChildIds = true;
                L.i("this Android build hides AccessibilityNodeInfo.getChildId; cannot read Compose UI");
            }
            return;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(AccessibilityNodeProvider.HOST_VIEW_ID);
        seen.add(AccessibilityNodeProvider.HOST_VIEW_ID);

        while (!queue.isEmpty() && out.size() < MAX_NODES) {
            int id = queue.poll();
            AccessibilityNodeInfo info;
            try {
                info = provider.createAccessibilityNodeInfo(id);
            } catch (Throwable t) {
                continue;
            }
            if (info == null) continue;
            if (id != AccessibilityNodeProvider.HOST_VIEW_ID) out.add(UiNode.fromVirtual(host, provider, id, info));

            for (int i = 0, n = info.getChildCount(); i < n; i++) {
                long childNodeId;
                try {
                    childNodeId = HiddenApi.childNodeId(info, i);
                } catch (Throwable t) {
                    L.e("reading accessibility child failed", t);
                    return;
                }
                int virtualId = (int) (childNodeId >> 32);
                // HOST_VIEW_ID / UNDEFINED mean "a real child View"; those are walked by the View pass.
                if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID || virtualId == UNDEFINED_ITEM_ID) continue;
                if (seen.add(virtualId)) queue.add(virtualId);
            }
        }
    }
}
