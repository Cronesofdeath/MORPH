import java.util.Properties

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val compactArm64 = providers.gradleProperty("compactArm64").map(String::toBoolean).orElse(false)
val inspectR8 = providers.gradleProperty("inspectR8").map(String::toBoolean).orElse(false)
val allowDevSign = providers.gradleProperty("allowDevSign").map { it == "true" }.orElse(false)
val localSigning = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun releaseCredential(name: String): String? =
    providers.environmentVariable(name).orNull?.takeUnless { it.isBlank() }
        ?: localSigning.getProperty(name)?.takeUnless { it.isBlank() }

val storePath = releaseCredential("MORPH_KEYSTORE_PATH")
val storePass = releaseCredential("MORPH_KEYSTORE_PASSWORD")
val releaseAlias = releaseCredential("MORPH_KEY_ALIAS")
val releaseKeyPass = releaseCredential("MORPH_KEY_PASSWORD")
val releaseStore = storePath?.let(rootProject::file)
val productionSigningReady = releaseStore?.isFile == true &&
    listOf(storePass, releaseAlias, releaseKeyPass).all { it != null }
if (compactArm64.get()) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/compact-arm64"))
}

android {
    namespace = "com.morph.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.morph.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
            if (!compactArm64.get()) abiFilters += "x86_64"
        }
    }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("APKs/Test files"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (productionSigningReady) create("production") {
            storeFile = releaseStore
            storePassword = storePass
            keyAlias = releaseAlias
            keyPassword = releaseKeyPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (inspectR8.get()) {
                proguardFiles("r8-inspection.pro")
            }
            signingConfig = when {
                productionSigningReady -> signingConfigs.getByName("production")
                allowDevSign.get() -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    packaging {
        if (compactArm64.get()) jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(productionSigningReady || allowDevSign.get()) {
            "Release signing is required. Set the four MORPH signing values in the environment or keystore.properties. Use -PallowDevSign=true only for development or CI."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(requireReleaseSigning)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

dependencies {
    implementation(libs.material)
    implementation(libs.icons.core)
    implementation(libs.icons.extended)
    implementation(libs.activity)
    implementation(libs.navigation)
    implementation(libs.lifecycle)
    implementation(libs.work)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.hilt.runtime)
    ksp(libs.hilt.compiler)
    implementation(libs.datastore)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.ffmpeg)
    implementation(libs.smart)
    implementation(libs.poi.core)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratch)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation(libs.pdf)
    implementation(libs.svg)
    implementation(libs.exif)
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation(libs.commonmark.core)
    implementation(libs.commonmark.tables)
    implementation(libs.jsoup)
    implementation(libs.compress)
    implementation(libs.xz)
    implementation(libs.zip)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.xml) { exclude(group = "com.fasterxml.woodstox") }
    implementation(libs.jackson.csv)
    implementation(libs.jackson.properties)
    implementation(libs.aalto)
    implementation(libs.stax)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
