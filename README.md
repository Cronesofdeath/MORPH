# MORPH

MORPH is an offline Android file converter with byte-verified compression. Convert images, video, audio, documents, spreadsheets, and archives. Set a size ceiling for a file or batch, or use exact-byte padding for compatible formats.

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 37. MORPH targets Android 15 and supports Android 8.0 and later on ARM64 and x86-64.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

The universal debug APK appears at `app/build/outputs/apk/debug/app-debug.apk`. For the compact, development-signed ARM64 APK, run `.\gradlew.bat :app:assembleRelease -PcompactArm64=true`. Its output is under `app/build/compact-arm64/outputs/apk/release/`. In the original local workspace, the `APKs` folder also holds copies of the packaged builds and test reports.

Try the [mislabeled JPEG fixture](APKs/Test%20files/messy-photo.png) with a 50 KiB cap. Open **Queue** to inspect the result and **Vault** to find saved conversions and recipes. See the [release audit](RELEASE-AUDIT.md) for test coverage and known format limitations.

## License

MORPH is licensed under the [GNU General Public License v3.0](LICENSE). The pinned [FFmpegKit full GPL release](https://github.com/ffmpegkit-maintained/ffmpeg/releases/tag/v8.1.9-lts-android) provides the native conversion engine. Third-party components retain their respective licenses.
