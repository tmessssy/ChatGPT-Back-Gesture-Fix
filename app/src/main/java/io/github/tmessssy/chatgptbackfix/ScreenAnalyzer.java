package io.github.tmessssy.chatgptbackfix;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides, from a {@link UiScanner.Snapshot}, two things:
 *  1. Is this the main chat screen? (a sidebar/menu button sits in the top-left corner)
 *  2. Is the sidebar currently open?
 *
 * Sidebar-open detection uses several independent signals, strongest first:
 *  - a visible "Close sidebar" / "Close navigation menu" style element
 *  - a left-anchored, drawer-shaped pane (pane title or dismiss action), as Material drawers expose
 *  - a learned "signature": the labels that appeared the last time this module opened the sidebar
 *    (self-calibrating, so it keeps working even if ChatGPT's sidebar has no standard labels)
 */
final class ScreenAnalyzer {

    /** Labels that identify the sidebar/menu button. */
    private static final String[] OPEN_WORDS = Text.padAll(
            "sidebar", "side bar", "side menu", "menu", "drawer", "navigation", "nav",
            "history", "chat history", "conversations", "conversation list", "chats");

    /** Labels that mean the top-left button is a Back/Up/Close button, i.e. we are on a sub-screen. */
    private static final String[] BACK_WORDS = Text.padAll(
            "back", "go back", "navigate up", "up", "close", "dismiss", "cancel", "exit", "done",
            "hide", "collapse", "return");

    /** Labels that only exist while the sidebar is open. */
    private static final String[] CLOSE_SIDEBAR_PHRASES = Text.padAll(
            "close sidebar", "close side bar", "hide sidebar", "collapse sidebar", "close side menu",
            "close navigation", "close navigation menu", "close navigation drawer", "close drawer",
            "close menu", "dismiss menu", "close chat history");

    /** Pane titles a navigation drawer typically announces. */
    private static final String[] PANE_WORDS = Text.padAll(
            "sidebar", "side bar", "navigation", "drawer", "menu", "history", "chats", "conversations");

    private static final int SIGNATURE_MAX = 40;
    private static final int SIGNATURE_MIN = 3;
    private static final int LABEL_MAX_LEN = 80;

    /** Learned once per ChatGPT process, refreshed every time the module opens the sidebar. */
    private static List<String> signature = Collections.emptyList();

    private ScreenAnalyzer() {
    }

    static final class Result {
        UiNode menuButton;
        /** Clickable nearest the top-left corner that isn't a Back/Close button (label may be unknown). */
        UiNode cornerButton;
        String sidebarOpenReason;
        boolean topLeftBackButton;
        final List<UiNode> topLeftClickables = new ArrayList<>();
        final Set<String> labels = new LinkedHashSet<>();
        int signatureHits;
    }

    static Result analyze(UiScanner.Snapshot s, List<String> extraLabels) {
        Result r = new Result();
        Rect win = s.window;
        int w = win.width();
        int h = win.height();
        int leftLimit = win.left + Math.round(w * 0.30f);
        int topLimit = win.top + Math.round(Math.min(h * 0.25f, 220 * s.density));

        String closeReason = null;
        String paneReason = null;
        String dismissReason = null;
        int bestCornerDistance = Integer.MAX_VALUE;
        int bestAnyDistance = Integer.MAX_VALUE;

        for (UiNode n : s.nodes) {
            if (!n.visible || n.bounds.isEmpty() || !Rect.intersects(n.bounds, win)) continue;

            if (n.hasLabel() && n.key.length() <= LABEL_MAX_LEN) r.labels.add(n.key);

            if (closeReason == null && Text.hasAny(n.match, CLOSE_SIDEBAR_PHRASES)) {
                closeReason = "close control \"" + n.display + "\"";
            }
            if (paneReason == null && Text.hasAny(n.paneTitle, PANE_WORDS) && isDrawerShaped(n.bounds, win)) {
                paneReason = "drawer pane \"" + n.paneTitle.trim() + "\"";
            }
            if (dismissReason == null && n.dismissible && isDrawerShaped(n.bounds, win)) {
                dismissReason = "dismissible drawer-shaped pane " + n.bounds.toShortString();
            }

            boolean topLeft = n.bounds.centerX() < leftLimit && n.bounds.centerY() < topLimit;
            if (!topLeft || !n.clickable || !n.enabled) continue;
            r.topLeftClickables.add(n);
            int d = (n.bounds.centerX() - win.left) + (n.bounds.centerY() - win.top);

            boolean extra = n.hasLabel() && matchesExtra(n.match, extraLabels);
            boolean back = !extra && Text.hasAny(n.match, BACK_WORDS);
            if (back) r.topLeftBackButton = true;
            if (!back && d < bestAnyDistance) {
                bestAnyDistance = d;
                r.cornerButton = n;
            }
            boolean isMenu = extra || (!back && Text.hasAny(n.match, OPEN_WORDS));
            if (isMenu && d < bestCornerDistance) {
                bestCornerDistance = d;
                r.menuButton = n;
            }
        }

        // The fingerprint is ignored on screens with a Back button top-left: a full-screen chat search
        // or history page can list the same chat titles as the sidebar without being the sidebar.
        String signatureReason = null;
        List<String> sig = signature;
        if (!sig.isEmpty() && !r.topLeftBackButton) {
            int hits = 0;
            for (String label : sig) if (r.labels.contains(label)) hits++;
            r.signatureHits = hits;
            int needed = Math.max(SIGNATURE_MIN, Math.round(sig.size() * 0.4f));
            if (hits >= needed) signatureReason = "learned sidebar labels (" + hits + "/" + sig.size() + ")";
        }

        if (closeReason != null) r.sidebarOpenReason = closeReason;
        else if (paneReason != null) r.sidebarOpenReason = paneReason;
        else if (dismissReason != null) r.sidebarOpenReason = dismissReason;
        else r.sidebarOpenReason = signatureReason;
        return r;
    }

    /**
     * Records which labels appeared because the sidebar opened. Returns the number of new labels,
     * and only stores them when there are enough to be a reliable fingerprint.
     */
    static int learnSignature(Set<String> before, Set<String> after) {
        LinkedHashSet<String> fresh = new LinkedHashSet<>();
        for (String label : after) {
            if (fresh.size() >= SIGNATURE_MAX) break;
            if (before.contains(label)) continue;
            if (label.length() < 2 || label.length() > LABEL_MAX_LEN) continue;
            if (!containsLetter(label)) continue;
            fresh.add(label);
        }
        if (fresh.size() >= SIGNATURE_MIN) {
            signature = Collections.unmodifiableList(new ArrayList<>(fresh));
        }
        return fresh.size();
    }

    static boolean hasSignature() {
        return !signature.isEmpty();
    }

    static List<String> signature() {
        return signature;
    }

    /** Left-anchored, 40-97% of the width, at least half the height: the shape of a navigation drawer. */
    private static boolean isDrawerShaped(Rect b, Rect win) {
        int w = win.width();
        return b.left <= win.left + Math.round(w * 0.03f)
                && b.width() >= w * 0.40f
                && b.width() <= w * 0.97f
                && b.height() >= win.height() * 0.50f;
    }

    private static boolean matchesExtra(String paddedMatch, List<String> extraLabels) {
        for (int i = 0, n = extraLabels.size(); i < n; i++) {
            if (Text.hasPhrase(paddedMatch, Text.padded(extraLabels.get(i)))) return true;
        }
        return false;
    }

    private static boolean containsLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }
}
