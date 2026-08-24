# Signage Kiosk Browser Launcher Test

This is the first test version for the Q96 TV box.

## Current features

- Android 9 / SDK 28 compatible
- Full-screen WebView browser
- Player URL setup screen
- Android TV launcher entry
- HOME launcher capability
- BOOT_COMPLETED receiver
- Landscape mode
- Keep screen awake
- Automatic reload after connection failure
- Separate package name so the current signage APK is not replaced

## Package name

`com.arjun.signagekiosktest`

## GitHub upload

Upload the CONTENTS of this folder to the root of a new GitHub repository.
Do not upload the ZIP without extracting it.

The repository root must contain:

```text
.github/
app/
build.gradle
gradle.properties
settings.gradle
README.md
```

## Build APK

1. Open the GitHub repository.
2. Open **Actions**.
3. Select **Build Signage Kiosk APK**.
4. Click **Run workflow**.
5. Wait for the green check mark.
6. Open the completed workflow.
7. Download **Signage-Kiosk-Browser-Test-APK** from Artifacts.
8. Extract the downloaded artifact ZIP.
9. Install `app-debug.apk` on the TV box.

## First test

1. Open the app manually.
2. Enter the player URL and select **Save and Open Player**.
3. Confirm the page loads.
4. Press the TV remote Home button.
5. Select **Signage Kiosk Browser Test** and choose **Always**, if prompted.
6. Disconnect power for 15 seconds.
7. Reconnect power without pressing any remote button.
8. Check whether this app opens automatically.

## Important

This is only the launcher test version. USB downloading and offline media playback will be added only after the Home launcher test succeeds.
