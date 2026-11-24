# MORPH local release candidate audit

Baseline: public v1.0.2, version code 3. Candidate: 1.0.3-rc1, version code 4. The repository has no test-fixtures directory. The existing sample is under APKs/Test files. This log distinguishes source changes from device verification.

Paths in the table are relative to app/src/main/kotlin/com/morph/app unless prefixed by app/.

| Finding | Source finding and disposition | Implementation |
|---|---|---|
| Raster vector export | Confirmed and removed. All image inputs previously targeted an SVG containing an embedded raster PNG. SVG input remains renderable. | domain/graph/FormatRegistry.kt, engine/ImageEngine.kt |
| Long conversion chains | Confirmed and restricted to direct conversions and one intermediate HTML, text or PDF representation. | domain/graph/FormatRegistry.kt |
| Desktop spreadsheet calls | Refuted for direct app calls. Neither autoSizeColumn nor java.awt nor javax.imageio is invoked by application source. The POI dependency includes optional desktop APIs and the R8 file contains desktop warning exclusions. This is not proof every possible POI path runs on ART. | engine/SpreadsheetEngine.kt, app/proguard-rules.pro |
| Sniffing | Confirmed. Unknown bytes previously fell back to their filename. Binary/text classification, compatible brands, UTF-16 text, portal reports and first-class byte correction now run from content. Text grammars overlap and classification remains heuristic. | domain/graph/MagicByteDetector.kt, domain/graph/ContentText.kt, StudioModel.kt, worker/ConversionWorker.kt |
| Spreadsheet fidelity | Confirmed that existing workbook migration already copied formulas, basic styling, widths and merged ranges. HTML lost that information. Added merged/typed display export, formula evaluation with cached fallback, portal span ingestion and expanded package budget. | engine/SpreadsheetEngine.kt, engine/PortalTables.kt |
| Unicode PDF crash | Refuted for the suspected Standard 14 font call. Text creation already used Android Paint and PdfDocument. Added actual-device Unicode coverage and an offline WebView document path. | engine/DocumentEngine.kt, engine/WebPagePdf.kt |
| Mixed or scanned PDF text loss | Confirmed. The original stripper wrote blank pages. The candidate checks each selected page and attempts bundled OCR for missing text, with a hard failure for unreadable results. | engine/PdfEngine.kt, engine/OfflineOcr.kt |
| Image handling | Existing JPEG rotation and alpha matting confirmed. Added explicit sRGB decode, structural animation and TIFF checks, animated output checks and advisories. | engine/ImageEngine.kt, domain/graph/ImageStructure.kt, engine/TargetSizeOrchestrator.kt |
| Media streams | Confirmed first-audio-only selection. Added compatible remux, multi-audio mapping on video transcode, text subtitle conversion and rejection for incompatible subtitle combinations. | engine/MediaEngine.kt |
| Archive paths and directories | Existing extraction uses private numbered files and checks logical paths. Empty directories were skipped. Retain directory entries and reject unsupported TAR special entries. | engine/ArchiveEngine.kt |
| SQL flattening | Confirmed nested nodes could become empty strings. Expand nested keys with collision detection before building tabular output. | engine/DataEngine.kt |
| Size and padding | Existing integer size checks verified in source. Removed textual formats whose padding changes content, retained JSON grammar whitespace. Added PNG CRC verification. | domain/model/SizeTarget.kt, engine/TargetSizeOrchestrator.kt, engine/PngIntegrity.kt |
| False completion | Confirmed worker previously published immediately after the converter returned. Added format-specific checks before export and an independent source/output hash comparison for correction. | engine/SemanticOutputValidator.kt, worker/ConversionWorker.kt |
| Concurrency and cancellation | Existing process-global worker mutex, 64 KiB transfer loop, startup work-directory sweep and per-session FFmpeg cancellation confirmed. | worker/ConversionWorker.kt, engine/ArchiveEngine.kt, engine/MediaEngine.kt, MorphApplication.kt |
| Tab overlap | Confirmed NavHost used default transitions and had no clipping boundary after inset padding. Candidate disables all four transitions, clips the opaque destination viewport and restores Vault search/segment selection. | ui/MorphApp.kt, ui/Library.kt |
| Purple controls | Confirmed default secondary containers were still active. Applied cobalt/ice-blue colors to selection roles. Existing opt-in dynamic colors and OLED preferences retained. | ui/MorphApp.kt |
| Upgrade | applicationId com.morph.app, existing signing configuration, database version 1 and schema retained. No migration required. | app/build.gradle.kts, data/Store.kt, MorphApplication.kt |

## Independent defects

- A UTF-16LE byte order mark matched an MPEG frame-sync prefix. A regression fixture with a Unicode HTML table now checks this ordering.
- The prior document package optimization selected embedded images by entry filename. It now sniffs the blob.
- The XLS-to-XLSX migration bypassed the workbook memory entry guard. Both editing paths now reject oversized inputs and declared expansion.
- An explicit user sample-rate override could bypass AMR and Opus encoder constraints.
- The archive-to-raw fast path could treat an empty directory as a standalone file. Single-file extraction now distinguishes directories.
- A compact portal HTML fragment beginning with a table or style element could previously enter the binary Excel reader.

## Verification record

The candidate was built locally on November 24, 2026. The reference commit is a504586 and the candidate changes are in the working tree.

| Gate | Command or evidence | Result |
|---|---|---|
| Final JVM suite | `gradlew.bat :app:testDebugUnitTest --offline --max-workers=2 --console=plain` | 37 passed, including read-back of SQL and XLSX exports containing a leading-zero ID, a boolean, null, a 64-bit integer and a decimal. |
| Android 8 update and tests | `adb install -r -t` followed by `adb shell am instrument -w com.morph.app.test/androidx.test.runner.AndroidJUnitRunner` | The universal test build updated version code 3 to 4. The package retained its original install timestamp. The runner reported OK for 25 tests. The API 29+ MediaStore scenario has an assumption guard on API 26. |
| Debug and instrumentation | `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest :app:lintDebug --offline --max-workers=2 --console=plain` | Success. Android 15 x86-64 ran 25 tests, including 13 conversion, nine stress, two PDF/OCR and one Compose navigation test. Zero failed or skipped. |
| Debug lint | `app/build/reports/lint-results-debug.html` | Zero errors and 34 warnings. The prior API 29 MediaStore error was resolved using an explicit version guard. Remaining warnings include dependency advisories, optional upstream TLS code, API targeting and Android KTX suggestions. |
| Compact minification | `gradlew.bat :app:assembleRelease -PcompactArm64=true --offline --max-workers=2 --console=plain` | R8 and release lint completed. The mapping is under `app/build/compact-arm64/outputs/mapping/release/`. |
| Android package | `APKs/MORPH-1.0.3-rc1-arm64.apk` | 38,715,460 bytes. SHA-256 `322107c0e3bce91b76aec3f91361289331cba9600cf2571001f9b55c60d069fb`. The packaged ABI is arm64-v8a. |
| Native alignment | `tools/Test-Arm64Alignment.ps1` and `zipalign -c -P 16 4` | 13 of 13 ARM64 libraries passed, including libmlkit_google_ocr_pipeline.so. Every inspected PT_LOAD segment reported 16384 and matching file/address offsets modulo 16384. ZIP alignment passed. |
| Upgrade identity | `aapt dump badging` and `apksigner verify --print-certs` | com.morph.app, version code 4, version 1.0.3-rc1. Signer SHA-256 `8969e25aa3926fb5d424ca23c8129c6967a3b9bc954135cbcc77db28d45e8187` matches the v1.0.2 compact release. Room schema stays at version 1. |
| Offline OCR | DeviceConversion and DocumentPdfReliability instrumentation, packaged permissions and native inspection | An image-only PDF produced the expected word OFFLINE and page-level OCR progress. Both recognition models are bundled. The final compact manifest has no INTERNET permission. |
| Distribution | Local outputs only | No tag, release, upload, push or deletion was performed. |

### Boundaries of the evidence

The automated checks cover the fixtures listed in the test classes. The Compose test establishes navigation and state restoration on an emulator. Optical ghosting and frame pacing at 120 Hz still need a physical display check. The OCR fixture exercises Latin text. Devanagari PDF generation was tested, while recognition quality on real Hindi scans still needs separate device evidence.

Large workbook edits use explicit compressed and expanded memory budgets. This candidate does not implement a general SAX/SXSSF path for arbitrarily large workbooks. The current spreadsheet and document validators establish that the relevant output parsers can open their files. Full equivalence of every source cell, embedded object or document layout is outside the evidence from those checks. TOML, INI, properties and plain-text identification overlaps for some byte sequences.
