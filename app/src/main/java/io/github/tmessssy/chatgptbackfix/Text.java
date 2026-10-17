package io.github.tmessssy.chatgptbackfix;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small string helpers for turning on-screen labels into something safe to match against. */
final class Text {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MAX_DISPLAY = 160;

    private Text() {
    }

    /** Human-readable label for logs: content description and text joined, whitespace collapsed. */
    static String display(CharSequence description, CharSequence text) {
        String d = clean(description);
        String t = clean(text);
        String out;
        if (d.isEmpty()) out = t;
        else if (t.isEmpty() || t.equalsIgnoreCase(d)) out = d;
        else out = d + " | " + t;
        return out.length() > MAX_DISPLAY ? out.substring(0, MAX_DISPLAY) + "..." : out;
    }

    /** Lowercase, punctuation stripped, single spaces: "Open Sidebar!" -> "open sidebar". */
    static String forMatching(CharSequence s) {
        if (s == null) return "";
        return NON_WORD.matcher(s.toString().toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Wraps a matching string in spaces so whole-word checks are a simple contains(). */
    static String padded(String matchText) {
        return matchText.isEmpty() ? "" : " " + matchText + " ";
    }

    /** Whole-word / whole-phrase test. "feedback" does not match "back". */
    static boolean hasPhrase(String paddedText, String paddedPhrase) {
        return !paddedText.isEmpty() && paddedText.contains(paddedPhrase);
    }

    static boolean hasAny(String paddedText, String[] paddedPhrases) {
        if (paddedText.isEmpty()) return false;
        for (String p : paddedPhrases) {
            if (paddedText.contains(p)) return true;
        }
        return false;
    }

    /** Pre-pads a phrase list once so matching never allocates. */
    static String[] padAll(String... phrases) {
        String[] out = new String[phrases.length];
        for (int i = 0; i < phrases.length; i++) out[i] = padded(forMatching(phrases[i]));
        return out;
    }

    private static String clean(CharSequence s) {
        if (s == null) return "";
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }
}
