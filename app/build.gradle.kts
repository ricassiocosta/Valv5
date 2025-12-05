plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ricassiocosta.me.valv5"
    compileSdk = 36

    defaultConfig {
        applicationId = "ricassiocosta.me.valv5"
        minSdk = 28
        targetSdk = 36
        versionCode = 39
        versionName = "2.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".dev"
        }
        applicationVariants.all {
            val variant = this
            variant.outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
                .forEach { output ->
                    val outputFileName =
                        "Vault_${variant.versionCode}_${variant.versionName}_${variant.buildType.name}.apk"
                    output.outputFileName = outputFileName
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.core.ktx)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    testImplementation(libs.junit)
    // Robolectric for JVM Android unit tests
    testImplementation("org.robolectric:robolectric:4.11")
    // BouncyCastle provider for ChaCha20-Poly1305 support on the JVM
    testImplementation("org.bouncycastle:bcprov-jdk15on:1.70")
    // Make BouncyCastle available to instrumentation tests as well
    androidTestImplementation("org.bouncycastle:bcprov-jdk15on:1.70")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.preference)
    implementation(libs.activity)
    implementation(libs.biometrics)

    implementation(libs.security.crypto)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.preferences)
    annotationProcessor(libs.glide.annotation)

    // Argon2id for password hashing (Signal's implementation)
    implementation(libs.argon2)

    // libsodium for streaming AEAD encryption (XChaCha20-Poly1305 secretstream)
    // JNA 5.5.0 is the version that lazysodium-android 5.1.0 was built with
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.5.0@aar")

    implementation(libs.glide)
    implementation(libs.about.libraries)
    implementation(libs.about.libraries.compose)
}

aboutLibraries {
    configPath = "config"
    // Remove the "generated" timestamp to allow for reproducible builds
    excludeFields = arrayOf("generated")
}

tasks.whenTaskAdded { // https://gist.github.com/obfusk/61046e09cee352ae6dd109911534b12e#fix-proposed-by-linsui-disable-baseline-profiles
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}