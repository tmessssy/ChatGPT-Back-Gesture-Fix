# ChatGPT Back Gesture Fix (LSPosed module)

Fixes back-gesture navigation in the ChatGPT Android app (`com.openai.chatgpt`):

| Where you are | Back gesture does |
|---|---|
| Main chat screen | Opens the sidebar |
| Sidebar open | Shows **Close app?** with **Cancel** / **Exit App** (or closes immediately, see Settings) |
| Keyboard open | Closes the keyboard (unchanged) |
| Opening ChatGPT | Keyboard stays closed until you tap the message box (setting, on by default) |
| Any sub-screen, dialog, sheet, menu | ChatGPT's normal back (unchanged) |

## Build (Android Studio)

1. **File > Open** and pick this `ChatGPT-Back-Gesture-Fix` folder. Let Gradle sync (first sync downloads Gradle 8.11.1 and AGP 8.7.3).
2. If asked, use the bundled JDK (17 or newer). SDK Platform 35 is required; Studio offers to install it if missing.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
   Output: `app/build/outputs/apk/debug/app-debug.apk`.
   `assembleRelease` also works and is signed with the debug key, so it installs without a keystore.

Do not accept prompts to add the Xposed API as `implementation`: it must stay `compileOnly`.

## Install

1. Install the APK on the phone.
2. LSPosed Manager > Modules > **ChatGPT Back Gesture Fix** > enable. Scope is pre-set to ChatGPT only.
3. Force stop ChatGPT (App info > Force stop), then open it.
4. Optional: tap the module in LSPosed to open its settings.

No reboot needed. It is an APK, not a Magisk/KernelSU zip.

## Settings

- **Enabled**: master switch, applies on the next back gesture.
- **Ask before closing**: on = "Close app?" prompt; off = closes right away.
- **Keep keyboard closed on open**: blocks ChatGPT's automatic keyboard pop-up for the first few seconds after it opens, until you touch the screen.
- **Debug logging**: writes each back decision and the on-screen labels to LSPosed Manager > Logs.
- **Extra sidebar button labels**: only if the logs show the sidebar button has a label the module does not recognize.

## How it works

Only Android framework methods are hooked, never ChatGPT's obfuscated code, so ChatGPT updates should not break it.

- **When to act**: ChatGPT closes on Back only when nothing inside the app wants that Back press. That path ends in the framework's `Activity.onBackPressed()` (or ChatGPT calling `finish()` / `moveTaskToBack()` while handling Back). The module intercepts exactly that moment and opens the sidebar instead. Sub-screens, sheets and menus that ChatGPT handles itself are never touched.
- **Opening the sidebar**: if the screen can be read (the same tree TalkBack uses), the module clicks the hamburger button it finds there. If not, it taps the hamburger's spot in the top-left corner, just under the status bar.
- **Sidebar open**: the module opened it and you have not touched the screen since, or the screen shows an open sidebar. Back then shows the "Close app?" prompt.
- **Both kinds of back delivery are handled**: the classic BACK key event, and Android 13+ predictive back (`OnBackInvokedCallback`, registered above ChatGPT's own so it can see every Back first and hand it on).

## Troubleshooting

1. Turn on **Debug logging**, force stop ChatGPT, reproduce, then check LSPosed Manager > Logs for `ChatGPTBackGestureFix`.
2. `loaded into com.openai.chatgpt` missing: the module is not enabled or ChatGPT was not restarted.
   `watching ...` shows which Back path ChatGPT uses and its version. Each sidebar open or exit logs one `back:` line.
3. `sidebarButton=null` on the main screen: look at the `top-left clickables` lines. Copy the sidebar button's label into **Extra sidebar button labels**.
4. `nodes=0` or `cannot read accessibility child IDs`: the Compose tree could not be read on this ROM. Send the log lines.

## Files

```
app/src/main/assets/xposed_init           entry point (io.github.tmessssy.chatgptbackfix.Hook)
app/src/main/java/io/github/tmessssy/chatgptbackfix/
  Hook.java               installs framework hooks, one BackController per Activity
  BackController.java     back logic, exit prompt, predictive-back registration
  BackInvokedBridge.java  Android 13+ OnBackInvokedCallback wrapper
  UiScanner.java          walks Views + Compose accessibility nodes
  UiNode.java             one on-screen element (label, bounds, click)
  ScreenAnalyzer.java     main-screen and sidebar-open detection
  HiddenApi.java          scoped hidden-API access (AccessibilityNodeInfo child IDs)
  ModuleConfig.java       reads settings inside ChatGPT (LSPosed shared prefs)
  SettingsActivity.java   settings screen (module's own process)
  Prefs.java, Text.java, L.java   shared keys, string matching, logging
```
