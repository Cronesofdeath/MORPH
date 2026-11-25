# MORPH release audit

## Version 1.1.0, version code 7

- Bottom tabs use a single visible panel with a short entry animation. Android 15 regression tests verified that switches dispose the outgoing panel, Vault search survives switching, and a shared file survives activity recreation.
- Excel XLS and XLSX exports paginate into column groups and break at row boundaries. The Android regression checked the rightmost column and last row in a wide workbook. An additional file-picker run with a private 68-column, 19-row workbook produced 11 A4 pages, with the far-right totals and bottom rows present.
- The debug JVM suite passed all 37 tests. The private workbook and its output stay outside version control.

## Version 1.0.0, version code 6

Local release verification was performed on November 24, 2026. The packaged ARM64 build uses the existing Android development certificate. It can update the prior public APK for local testing. Production signing requires maintainer credentials.

| Gate | Command | Result |
|---|---|---|
| JVM | `.\gradlew.bat :app:testDebugUnitTest` | 37 tests passed |
| Debug lint | `.\gradlew.bat :app:lintDebug` | 0 errors, 35 warnings |
| Signing without credentials | `.\gradlew.bat :app:requireReleaseSigning -PcompactArm64=true` | Rejected with the expected signing message |
| Compact ARM64 | `.\gradlew.bat :app:assembleRelease -PcompactArm64=true -PallowDevSign=true -PinspectR8=true` | R8, resource shrinking, vital lint and packaging passed in 10m 44s |
| Package | `.\tools\Test-ReleasePackage.ps1 -Archive app/build/compact-arm64/outputs/apk/release/app-release.apk` | APK Signature Scheme v2 verified, 13 of 13 ARM64 libraries 16 KB aligned, both ART baseline profiles present |
| Android 15 x86-64 | `adb shell am instrument -w com.morph.app.test/androidx.test.runner.AndroidJUnitRunner` | 27 passed in 79.711 s, including share intake from Vault, activity recreation, 50 KiB conversion of the mislabeled fixture, and impossible-cap cleanup |
| Minified runtime smoke | `.\gradlew.bat :app:assembleRelease -PallowDevSign=true`, then `.\tools\Test-DocumentPicker.ps1 -Serial emulator-5570 -CapKiB 50` and the same command with `-CapKiB 100` | Universal minified build installed and passed the picker-to-Downloads flow at both caps. 50 KiB produced 47,894 bytes in 19.54 s, and 100 KiB produced 100,282 bytes in 17.25 s. Times include UI automation. |

Lint warnings include KTX and version-catalog suggestions, free-space hints, optional TLS code in upstream libraries, and platform or dependency advisories. The two command-line builds used `--offline --max-workers=2 --console=plain`.

### Compact footprint

DEX and native metrics sum uncompressed ZIP entries. APK size is the on-disk download size.

| Metric | Published build 5 | Local build 6 |
|---|---:|---:|
| DEX count | 3 | 3 |
| DEX bytes | 15,824,636 | 14,720,136 |
| ARM64 shared library bytes | 47,420,840 | 47,420,840 |
| APK bytes | 38,715,540 | 37,569,764 |
| ART baseline profile bytes | 12,436 | 12,391 |
| ART baseline profile metadata bytes | 1,625 | 1,635 |

The active R8 configuration includes the optimized Android defaults and dependency consumer rules. Application rules remove debug and verbose logging and replace the PDFBox package-wide keep rule with the narrower font rule. The POI schema reflection and FFmpegKit JNI rules remain in place.

### Merged release manifest permission dump

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_DATA_SYNC
android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING
android.permission.POST_NOTIFICATIONS
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
com.morph.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

The packaged APK declared the same permission set. Both checks excluded `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.

The compact package also passed `zipalign -c -P 16 4`. Its SHA-256 is `f998a71d49e9e0eb4a8aa299959c7d51ebddd802044a637fc0b862abd78c0d64`.

### Maintainer gates

- Configure the production certificate and decide how to handle updates from the existing public build, which used development signing.
- Run the compact APK on a physical ARM64 phone, including on 16 KB-page Android if available. The minified runtime smoke above exercised the universal APK on x86-64.
- Confirm hosted CI on the first branch push or pull request, and verify redistribution terms and corresponding source for the shipped native and OCR components.
- Exercise forced process death on a physical device. The local suite covers activity recreation and persisted Room history, and the minified install retained visible Queue and Vault entries.
