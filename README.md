# Room Code Auto Copy — Android starter prototype

This is a **starter project, not a tested production APK**.

## What it contains
- Android MediaProjection screen capture with user consent and an ongoing system notification
- Google ML Kit OCR
- Percentage-based scan-region configuration
- Automatic clipboard copy and a short `Copied!` toast
- Basic duplicate suppression
- Conservative starter candidate rules: 6–12 digit numeric tokens, or 6–16 character mixed letter/digit tokens

## Build
1. Install Android Studio and a supported JDK.
2. Open this folder in Android Studio.
3. Allow Gradle Sync to download dependencies.
4. Use **Build > Build APK(s)**, then install the debug APK on your Android device.

## First test
1. Open the app and set the scan region using percentages of the captured screen:
   - `x`: distance from left edge
   - `y`: distance from top edge
   - `width` and `height`: region size
2. Tap **Save region**, then **Start watching screen**, and approve Android's screen-capture prompt.
3. Switch to YouTube Live and test with a visible room code. Switch to eFootball and paste.
4. Tap **Stop watching** in the app when done.

## Limitations you should know before relying on it
- Region selection is percentage-based; it is not yet a drag-to-select rectangle. Tune it for each streamer's layout and screen orientation.
- Exact Room Code format is not confirmed. Current rules may miss codes outside the configured lengths or still select another matching string in the chosen region. Update the rules once you know the exact format.
- OCR runs on captured frames and can lag or miss a briefly displayed code.
- MediaProjection restrictions vary by Android version/device. Android 14+ lifecycle rules are handled with a callback, but real-device testing is still required.
- Protected/secure surfaces may not be captured. Android shows a system notification during capture.
- Frames are processed locally; the app does not upload or save them.
- The source has not been built or tested in this environment. Do not rely on it for a live match until tested on your phone.
## Cloud APK build option (GitHub Actions)
A workflow is included at `.github/workflows/build-apk.yml`. To use it:
1. Create a GitHub repository and upload the contents of this project folder (not the ZIP itself).
2. Open the repository's **Actions** tab.
3. Select **Build Room Code Auto Copy APK**, then **Run workflow**.
4. If the build succeeds, open the completed run and download the `RoomCodeAutoCopy-debug-apk` artifact.
5. Extract the downloaded artifact ZIP to get `app-debug.apk`.

This cloud workflow is an alternative build path, not proof that the app compiles or works. If the build fails, open the workflow log and use the first actual error to fix the project. The generated APK is a debug build and is not a Play Store release.
