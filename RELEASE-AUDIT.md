# MORPH 1.0.2 release audit

**Decision.** Greenlit for private hands-on testing. Public release requires the physical-device and format-fidelity checks in this report.

## Verification results

| Suite | Result |
| --- | --- |
| JVM unit and regression tests | 27 passed |
| Android 8.0 x86-64 emulator | 20 passed. The automatic Downloads save failure check applies to Android 10 and later. |
| Android 15 x86-64 emulator | 21 passed, including the automatic save failure check. |

The optimized universal build also installed on Android 15, accepted shared text, converted it to PDF, and saved the result to Downloads. The full debug lint run was stopped when it held Windows build files open during the compatibility rerun. Android's release-critical lint checks passed during packaging.

All three APKs were rebuilt. The compact ARM64 APK is 31,247,064 bytes. It passed signature, ABI, 16 KB native alignment, and offline manifest checks. It uses the same signing key as the previous test build.

SHA-256 for the tested local `MORPH-arm64.apk` is `f166e6cc264d35c9ef01c5db15f89465b8913fab0226ed7badd3c6a4767f8713`.

The local workspace holds the HTML reports under `APKs/Test reports`. The commands below regenerate the reports under `app/build/reports`.

## Findings addressed

| Finding | Change |
| --- | --- |
| Duplicate, blank, or ragged CSV headers could overwrite columns in JSON, XML, and SQL exports. | Allocate a unique key for every column and retain trailing values. |
| OpenDocument repeat markers could drop cells or shift columns. | Expand meaningful repeated rows and columns within a bounded editing budget. |
| Invalid Base64 could decode partially and appear successful. | Stream input with strict alphabet, padding, and trailing-bit checks, while accepting line wrapping. |
| Concatenated BZIP2 and XZ streams could lose their later members. | Decode all members and bound archive decoder memory. |
| A renamed single GZIP stream could be mistaken for a compressed TAR. | Inspect the decompressed header before identifying TAR. |
| A short BMP signature could misclassify ordinary text. | Validate the bitmap header before selecting that format. |
| Scientific notation with extreme exponents and long Unicode filenames could break input handling. | Bound decimal input and limit sanitized filenames by UTF-8 bytes. |
| A slow worker could race a cancellation or a revoked save destination could mark a converted file failed. | Make terminal status changes conditional, serialize conversion engines, keep a verified Vault output if automatic saving fails, and move verified files into place when storage permits. |
| The older bitmap decoder could disregard requested dimensions and JPEG rotation. | Apply both dimension limits and honor EXIF orientation in the fallback. |
| Archive conversion exposed one password for two different operations. | Accept separate input and output passwords and protect both with Android Keystore. |
| Tiny JPEG padding gaps and ambiguous PDF page ranges handled edge cases poorly. | Support legal JPEG marker fill and validate PDF ranges before processing. |
| Duplicate JSON keys or concatenated JSON values could silently discard data. | Reject ambiguous input and preserve decimal precision. |

## Release gates

- Test the signed ARM64 build on a physical phone. Exercise HEIC, 4K video, a multi-gigabyte file, low free storage, and cancellation with the screen locked. Verify outputs after the OS stops and restarts a job.
- Compare representative complex Office files and ebooks with the source applications. Legacy PPT export uses slide text. Complex document layout and workbook features have limited fidelity.
- Exercise real document providers and revoked folder access on physical phones. The automated suite uses local emulator storage.
- Review the resolved dependencies and native codec provenance for distribution. The FFmpegKit full GPL distribution and the Android PDFBox fork warrant a maintained dependency inventory and review. The upstream PDFBox embedded-file advisories describe an examples module. MORPH uses the Android library and has its own archive extraction path. [FFmpegKit releases](https://github.com/ffmpegkit-maintained/ffmpeg/releases) · [Apache PDFBox advisories](https://pdfbox.apache.org/security.html) · [Apache POI guidance](https://poi.apache.org/security.html)
- Configure production signing before public distribution. The compact APK is signed with the development key for in-place upgrades during testing.

## Reproduce on Windows

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:assembleRelease -PcompactArm64=true
```

The test suites use generated fixtures. Coverage includes a 144-megapixel PNG, a 256 MiB streamed archive with a trailing member, a 50-image PDF, multiple JPEG padding boundaries, exact PDF reopening, encrypted ZIP password rotation, malformed input, numeric limits, and the ordinary audio, video, and archive output matrices.
