plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.drivecontacts"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.drivecontacts"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        // Required by the Proton Drive SDK usage guidelines: external-drive-{name}@{semver}-{channel}.
        buildConfigField("String", "PROTON_APP_VERSION", "\"external-drive-drivecontacts@0.1.1-alpha\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Release signing only when all four values are set (Gradle properties or environment).
    val releaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { name ->
        providers.gradleProperty("drivecontacts.$name")
            .orElse(providers.environmentVariable("DRIVECONTACTS_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()))
            .orNull
    }
    val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrEmpty() }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigning.getValue("storeFile")!!)
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        // The unit tests call android.util.Log.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/licenses/**", "META-INF/LICENSE*", "META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.proton.drive.sdk)
    implementation(libs.proton.core.network.data)
    implementation(libs.proton.core.network.domain)
    implementation(libs.proton.core.domain)
    implementation(libs.proton.core.util.kotlin)
    implementation(libs.proton.crypto.golib)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.browser)
    implementation(libs.work.runtime)
    implementation(libs.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
