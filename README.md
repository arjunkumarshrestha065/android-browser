# Offline USB Signage Browser

## Functions

- Select external USB folder once
- Permanently remember USB-folder permission
- Download the assigned client/group playlist from `/api/client/playlist/:token`
- Save videos and images under `SignageKiosk/media`
- Save the active playlist under `SignageKiosk/data/playlist.json`
- Play saved files after restart without internet
- Download only missing files
- Keep old playlist if synchronization fails
- Delete files no longer assigned after the new playlist is saved
- Android 9 SDK 28 compatible
- HOME and Android TV launcher support
- GitHub Actions APK build

## USB folder structure

```text
Selected USB Folder/
└── SignageKiosk/
    ├── media/
    ├── data/
    │   └── playlist.json
    └── temp/
```

## GitHub upload

Extract this ZIP. Upload everything INSIDE `Signage-Kiosk-Browser-Offline` to the root of the GitHub repository.
If `.github` does not upload, create `.github/workflows/build-apk.yml` manually on GitHub.

## Build

Open Actions, choose **Build Offline USB Signage APK**, run the workflow, then download **Signage-Kiosk-Offline-USB-APK** from Artifacts.

## First setup on TV box

1. Install and open the APK.
2. Enter server URL without `/player.html`, for example `http://10.10.10.20:3000`.
3. Enter the existing client token.
4. Select **Select USB Storage Folder**.
5. Choose the USB drive or a folder inside it.
6. Select **Use this folder** and allow access.
7. Select **Save and Start**.
8. Keep internet connected until first synchronization completes.
9. Disconnect internet and restart the box to test offline playback.

## Important

The server must return `items` containing `full_url` or `file_url`, `file_name`, `file_type`, and `duration` from `/api/client/playlist/:token`.
