# MORPH third-party notices

MORPH source is licensed under [GNU GPL v3.0](LICENSE). Bundled components have their own licenses and notices. The version catalog identifies Gradle coordinates and versions.

| Component | Pinned version | License and upstream |
|---|---|---|
| FFmpegKit full GPL and statically linked codecs | 8.1.9, Android tag v8.1.9-lts-android | [GPL-3.0 distribution and native source](https://github.com/ffmpegkit-maintained/ffmpeg/releases/tag/v8.1.9-lts-android). See the pinned upstream source for component-specific terms and build scripts. |
| Smart Exception Java | 0.2.1 | [BSD-3-Clause](https://github.com/arthenica/smart-exception) |
| AndroidX Compose Material3, icons, Activity, Navigation, Lifecycle, WorkManager, Room, DataStore, ExifInterface, HeifWriter | See [version catalog](gradle/libs.versions.toml) and [app Gradle file](app/build.gradle.kts) | [Apache-2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| Kotlin and kotlinx.coroutines | Kotlin 2.3.21, coroutines resolved transitively | [Apache-2.0](https://github.com/JetBrains/kotlinx.coroutines/blob/master/LICENSE.txt) |
| Dagger and Hilt | 2.59.2 | [Apache-2.0](https://github.com/google/dagger/blob/master/LICENSE.txt) |
| Coil Compose and Video | 3.3.0 | [Apache-2.0](https://github.com/coil-kt/coil/blob/main/LICENSE.txt) |
| Apache POI, POI OOXML and Scratchpad | 5.5.1 | [Apache-2.0](https://poi.apache.org/) |
| Apache XMLBeans and OOXML schemas | Resolved through POI | [Apache-2.0](https://xmlbeans.apache.org/) |
| PDFBox-Android | 2.0.27.0 | [Apache-2.0](https://github.com/TomRoush/PdfBox-Android) |
| AndroidSVG | 1.4 | [Apache-2.0](https://github.com/BigBadaboom/androidsvg) |
| CommonMark Java and GFM tables | 0.24.0 | [BSD-2-Clause](https://github.com/commonmark/commonmark-java/blob/main/LICENSE.txt) |
| jsoup | 1.21.1 | [MIT](https://jsoup.org/license) |
| Commons Compress and companion Commons libraries | Compress 1.28.0 | [Apache-2.0](https://commons.apache.org/proper/commons-compress/) |
| XZ for Java | 1.10 | [0BSD](https://tukaani.org/xz/java.html) |
| Zip4j | 2.11.5 | [Apache-2.0](https://github.com/srikanth-lingala/zip4j) |
| Jackson core, databind and YAML, XML, CSV and properties modules | 2.19.2 | [Apache-2.0](https://github.com/FasterXML/jackson) |
| SnakeYAML | Resolved through Jackson YAML | [Apache-2.0](https://bitbucket.org/snakeyaml/snakeyaml/) |
| Aalto XML | 1.3.3 | [Apache-2.0](https://github.com/FasterXML/aalto-xml) |
| StAX API | 1.0-2 | The published metadata lists GPL and CDDL 1.0. See the [upstream artifact metadata](https://repo.maven.apache.org/maven2/javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2.pom). |
| Google ML Kit bundled Latin and Devanagari text recognition and Google Play services support libraries | 16.0.1 | [Google ML Kit Terms](https://developers.google.com/ml-kit/terms), which incorporate the Google APIs Terms. |
| Bouncy Castle provider and PKIX support | Resolved through PDFBox-Android | [Bouncy Castle permissive license](https://www.bouncycastle.org/licence.html) |

Before distributing a newly signed build, retain the upstream license and notice files and make the corresponding source for the exact GPL native binaries available under the applicable distribution terms. Verify obligations for the bundled Google SDK and models separately.
