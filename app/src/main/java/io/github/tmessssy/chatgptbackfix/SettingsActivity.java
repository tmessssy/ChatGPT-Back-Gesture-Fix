package io.github.tmessssy.chatgptbackfix;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Module settings, opened from LSPosed Manager. Runs in the module's own process, so it must not
 * touch XposedBridge (no L / ModuleConfig here).
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private boolean moduleActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyWindowInsets();

        prefs = openPrefs();

        TextView status = findViewById(R.id.status);
        status.setText(moduleActive ? R.string.status_active : R.string.status_inactive);
        status.setTextColor(getColor(moduleActive ? R.color.status_ok : R.color.status_warn));

        bindSwitch(R.id.switch_enabled, Prefs.KEY_ENABLED, Prefs.DEF_ENABLED);
        bindSwitch(R.id.switch_confirm, Prefs.KEY_CONFIRM_EXIT, Prefs.DEF_CONFIRM_EXIT);
        bindSwitch(R.id.switch_keyboard, Prefs.KEY_KEEP_KEYBOARD_CLOSED, Prefs.DEF_KEEP_KEYBOARD_CLOSED);
        bindSwitch(R.id.switch_debug, Prefs.KEY_DEBUG, Prefs.DEF_DEBUG);

        EditText labels = findViewById(R.id.edit_labels);
        labels.setText(prefs.getString(Prefs.KEY_EXTRA_LABELS, Prefs.DEF_EXTRA_LABELS));
        labels.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString(Prefs.KEY_EXTRA_LABELS, s.toString().trim()).apply();
            }
        });

        // Write defaults once so the file exists and ChatGPT's process can read it right away.
        if (!prefs.contains(Prefs.KEY_ENABLED)) {
            prefs.edit()
                    .putBoolean(Prefs.KEY_ENABLED, Prefs.DEF_ENABLED)
                    .putBoolean(Prefs.KEY_CONFIRM_EXIT, Prefs.DEF_CONFIRM_EXIT)
                    .putBoolean(Prefs.KEY_KEEP_KEYBOARD_CLOSED, Prefs.DEF_KEEP_KEYBOARD_CLOSED)
                    .putBoolean(Prefs.KEY_DEBUG, Prefs.DEF_DEBUG)
                    .putString(Prefs.KEY_EXTRA_LABELS, Prefs.DEF_EXTRA_LABELS)
                    .apply();
        }
    }

    private void bindSwitch(int id, String key, boolean def) {
        Switch sw = findViewById(id);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    /**
     * LSPosed makes MODE_WORLD_READABLE work (and redirects the file somewhere ChatGPT can read) only
     * while the module is enabled. Otherwise Android throws, which doubles as an "is it active" check.
     */
    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            SharedPreferences p = getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
            moduleActive = true;
            return p;
        } catch (SecurityException e) {
            moduleActive = false;
            return getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        }
    }

    /** Android 15 draws apps edge to edge; keep content clear of the status and navigation bars. */
    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        View content = findViewById(R.id.content);
        final int l = content.getPaddingLeft();
        final int t = content.getPaddingTop();
        final int r = content.getPaddingRight();
        final int b = content.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                il = in.left;
                it = in.top;
                ir = in.right;
                ib = in.bottom;
            } else {
                il = insets.getSystemWindowInsetLeft();
                it = insets.getSystemWindowInsetTop();
                ir = insets.getSystemWindowInsetRight();
                ib = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(l + il, t + it, r + ir, b + ib);
            return insets;
        });
    }
}
