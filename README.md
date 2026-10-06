# Taobao Live Translate (Android)

Reads the Chinese text in the Taobao app and draws the translation directly over it, live. Everything runs on your phone (reading and translation), so it is free and works offline after a one-time language-pack download.

## How it works

It is an **accessibility service**, the same Android feature screen readers use.

1. Android hands it Taobao's **on-screen text with exact positions** (the accessibility tree), so there is no guessing from pixels.
2. **ML Kit Translate** translates each piece of text on the phone (results are cached).
3. The first time a piece of text appears, one **screenshot** measures its background and text colours, where the characters actually sit, and how big they are. Text sitting on a photo or banner is left alone.
4. A touch-transparent **overlay** paints each translation over the Chinese in matching colours (gradients included), sized to fit inside the original element.
5. When you scroll, Android reports how far, so the translations **move with the page** straight away. Very fast flings hide them for a moment instead.
6. Android 14+: once the screen settles, **OCR** reads a screenshot of Taobao's window to catch text the accessibility tree doesn't list.

Your taps and scrolls pass straight through to Taobao.

## Build and install

1. Install [Android Studio](https://developer.android.com/studio).
2. **File > Open** and choose this `TaobaoTranslate` folder. Let Gradle sync finish (it creates the Gradle wrapper and downloads dependencies; accept any "update AGP/Kotlin" suggestion if offered).
3. On your phone: enable **Developer options** and **USB debugging**, plug it in, and press **Run**.
   Alternatively use **Build > Build APK(s)** and install the APK on the phone.

Requires Android 11 or newer.

## Build without Android Studio (GitHub)

1. Create a free account at github.com and a new repository (Add file > Upload files works fine, no command line needed).
2. Unzip this project and upload **all** of its contents, including the hidden `.github` folder. If your computer hides it and it doesn't upload, in the repository choose **Add file > Create new file**, name it `.github/workflows/build.yml` and paste the contents of that file from this project.
3. Add the signing key secrets (see **Automatic updates** below). The build stops with an error until they exist.
4. Open the **Actions** tab. The "Build APK" run starts by itself (or press **Run workflow**). It takes about 5-10 minutes.
5. When it shows a green tick, the APK is attached to a new entry under **Releases** (and also under Artifacts at the bottom of the run).

If the run fails, open it and copy the red error text; it usually points straight at the problem.

## Automatic updates

Every successful build publishes a GitHub **Release** with a higher version number, signed with the same key each time, so the phone can update in place.

**One-time: signing key secrets.** In the repository on github.com: **Settings > Secrets and variables > Actions > New repository secret**. Add two secrets:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the whole contents of `signing/KEYSTORE_BASE64.txt` |
| `KEYSTORE_PASSWORD` | the contents of `signing/KEYSTORE_PASSWORD.txt` |

The `signing/` folder is on your computer only (it is never committed). **Back it up.** If the key is lost, the next version can't update the installed app: you'd have to uninstall and set it up again.

**On the phone: Obtainium.** Install [Obtainium](https://github.com/ImranR98/Obtainium) (download the APK from its Releases page). In Obtainium tap **Add app**, paste this repository's URL, and tap **Add**, then **Install**. Obtainium checks for new releases in the background and installs them. Apps installed through Obtainium also usually avoid Android's "Restricted setting" block on the accessibility switch.

## First run

Tap **Start translating Taobao**. You will be asked, in order, to:

1. Allow **notifications** (for the Pause and Turn off buttons).
2. Let it **download the language pack** (once, needs internet).
3. Turn on **Taobao Live Translate** in Accessibility settings. It is often listed under "Installed apps" or "Downloaded apps". Android warns that the app can see your screen; it only acts on Taobao and nothing leaves your phone.

**Android 13 and newer:** if the switch is greyed out ("Restricted setting"), go back to the app, tap **Allow restricted settings**, choose **⋮ > Allow restricted settings** on the page that opens, then turn the service on.

After that it stays on: translations appear whenever Taobao is open. Use the notification's **Pause** and **Turn off** buttons, or the **Turn off** button in the app. Changing the language in the app and tapping Start switches it immediately.

## Known limits

- **Text inside product photos and banners** stays in Chinese on purpose: a flat box over a picture looks worse than the original.
- **Payment and some secure screens** block screenshots. They stay in Chinese.
- **Android 11 to 13** have no OCR backup, so the few Taobao elements that don't expose their text stay in Chinese.
- **New text** (just scrolled in) shows in Chinese for about half a second while its colours are measured and it is translated. Text already seen comes back instantly.
- **Translation quality** is machine translation of product titles. Expect rough but usable results.
- **Khmer is not available**: ML Kit's on-device translation does not support it. For Khmer you would need a cloud service (e.g. Google Cloud Translation API), which costs money and needs an API key.

## Files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Language choice, language-pack download, setup help, launches Taobao |
| `TranslateAccessibilityService.kt` | Reads Taobao's text, follows scrolling, screenshots, OCR backup, translation |
| `ColorSampler.kt` | Measures background and text colours, text position and line height from a screenshot |
| `OverlayView.kt` | Lays out and draws the translated text boxes |
| `res/xml/accessibility_service_config.xml` | What the accessibility service is allowed to see and do |
