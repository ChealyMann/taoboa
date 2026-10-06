# Taobao Live Translate (Android)

Reads the Chinese text on your screen while you use Taobao and draws the translation directly over it, live. Everything runs on your phone (OCR and translation), so it is free and works offline after a one-time language-pack download.

## How it works

1. **Screen capture** (MediaProjection) grabs frames of whatever is on screen.
2. **Change detection** compares small fingerprints of frames, so OCR only runs when you scroll or open a new page.
3. **ML Kit Chinese OCR** finds text blocks and their positions.
4. **ML Kit Translate** translates each block (results are cached).
5. A touch-transparent **overlay** paints the translation on top of the original Chinese, with a background colour sampled from the page so it stays readable.

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
3. Open the **Actions** tab. The "Build APK" run starts by itself (or press **Run workflow**). It takes about 5-10 minutes.
4. When it shows a green tick, open the run and download **TaobaoTranslate-apk** from the Artifacts section at the bottom. Unzip it to get `app-debug.apk`.
5. Copy `app-debug.apk` to your phone, open it, and allow "Install unknown apps" when asked.

If the run fails, open it and copy the red error text; it usually points straight at the problem.

## First run

Tap **Start translating Taobao**. You will be asked, in order, to:

1. Allow **Display over other apps** (then tap Start again).
2. Allow **notifications**.
3. Let it **download the language pack** (once, needs internet).
4. Allow **screen capture** ("Start now").

Taobao then opens with translations over the Chinese text. Use the notification's **Pause** and **Stop** buttons. Pause when watching videos or live streams, since constantly changing content makes the overlay refresh a lot.

## Known limits

- **Payment and some secure screens** block screen capture. They stay in Chinese.
- **Translation quality** is machine translation of product titles. Expect rough but usable results.
- **Short delay**: after a scroll stops, translations take about a second to appear. While a page is scrolling you will briefly see the original Chinese.
- **Portrait only**: the overlay is sized for portrait orientation.
- **Khmer is not available**: ML Kit's on-device translation does not support it. For Khmer you would need a cloud service (e.g. Google Cloud Translation API), which costs money and needs an API key.

## Files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Permissions, language choice, language-pack download, launches the service and Taobao |
| `TranslateService.kt` | Screen capture, change detection, OCR, translation, overlay management |
| `OverlayView.kt` | Draws translated text boxes |
