# InstaDownloader

An Android app for downloading Instagram photos and videos.

## Features

- Download photos and videos from Instagram posts and reels
- Supports multi-photo carousel posts
- Persistent login — log in once, session is saved automatically
- Share an Instagram URL directly from the Instagram app to open it instantly

## Usage

### Method 1: Share from Instagram

1. Open a post in the Instagram app
2. Tap **Share → Copy Link** or **Share to...** and select **InstaDownloader**
3. The post loads automatically
4. Tap **Download All** to save

### Method 2: Paste a URL

1. Open InstaDownloader
2. Paste an Instagram post URL into the input bar (e.g. `https://www.instagram.com/p/ABC123/`)
3. The page loads automatically after pasting
4. Tap **Download All** to save

### First-time login

If you are not logged in, the app will show the Instagram login page. Log in once — your session is saved and you won't need to log in again.

### Downloaded files

Files are saved to:
```
/storage/emulated/0/Download/Instagram/
```

## Build

### Requirements

- Android Studio or JDK 17+
- Android SDK (API 24+)

### Build locally

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Build via GitHub Actions

Push to `main` or `master` — the APK is built automatically and available under **Actions → Artifacts**.

## Requirements

- Android 7.0 (API 24) or higher
- Internet connection
- Instagram account
