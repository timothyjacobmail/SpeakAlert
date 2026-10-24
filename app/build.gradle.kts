import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing comes from the git-ignored local.properties (speakalert.storeFile,
// .storePassword, .keyAlias, .keyPassword), so no credentials live in the repository.
// Without them, debug builds still work; only release builds need them.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseStoreFile: String? = localProps.getProperty("speakalert.storeFile")

android {
    namespace = "com.ghostgramlabs.speakalert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostgramlabs.speakalert"
        minSdk = 26
        targetSdk = 36
        versionCode = 61
        versionName = "2.0.40"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("speakalert.storePassword")
                keyAlias = localProps.getProperty("speakalert.keyAlias")
                keyPassword = localProps.getProperty("speakalert.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "SHOW_DEBUG_OPTIONS", "false")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Install beside the store/release app without replacing its signed package or data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "SHOW_DEBUG_OPTIONS", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // Unit tests run against a stub android.jar whose methods throw by default, so a
            // stray android.util.Log call fails a test that has nothing to do with logging.
            // Returning defaults makes those calls the no-ops they are on the JVM.
            isReturnDefaultValues = true
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    // Per-app language (AppCompatDelegate.setApplicationLocales) across API 26+.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.4")

    // Room
    val roomVersion = "2.6.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Accompanist (Permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-session:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")

    // Google Play Review
    implementation("com.google.android.play:review-ktx:2.0.1")

    // WorkManager (for boot-time alarm rescheduling outside BOOT_COMPLETED context)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Wear OS connectivity detection from phone app
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.11.0")
    testImplementation("org.mockito:mockito-inline:4.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
