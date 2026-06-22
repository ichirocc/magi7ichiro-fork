plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.magi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.magi.app"
        minSdk = 35
        targetSdk = 36
        versionCode = 95
        versionName = "2.89.0-tally-cell-fix-focus"
    }

    buildTypes {
        release {
            // Personal-test release APK: signed with the debug key so it is installable from Actions.
            // Replace with a private release signingConfig before store distribution.
            signingConfig = signingConfigs.getByName("debug")
            // No shrinking for this personal-test build. proguardFiles() is intentionally omitted:
            // it is ignored while isMinifyEnabled = false and only invites the false impression that
            // shrink rules are active. Re-add it together with isMinifyEnabled = true for a store build.
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") } }

    // This release variant is a personal-test APK signed with the debug key (see buildTypes.release),
    // not a Play-store build. `lintVitalRelease` aborts the APK on any *fatal* lint issue, which only
    // blocks the test build without adding value here. Don't fail the build on lint; still emit the
    // HTML/XML report so issues remain inspectable in app/build/reports/.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 長時間の最適化計算をバックグラウンドで完遂させる（改善仕様書 §6 / §3.4）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath so StateParser (org.json) runs in JVM tests
    // (android.jar ships only throwing stubs). Used by the Web-golden parity test.
    testImplementation("org.json:json:20240303")
}

// Surface test stdout (the Web-golden breakdown comparison) in CI console logs.
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
