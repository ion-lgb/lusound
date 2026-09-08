plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "app.lusound"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.lusound"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            val signingPath = providers.environmentVariable("LUSOUND_KEYSTORE").orNull
            if (signingPath != null) {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("LUSOUND_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("LUSOUND_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("LUSOUND_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("com.github.houbb:opencc4j:1.14.0")
    implementation("org.jellyfin.sdk:jellyfin-core:1.8.12")
    // SDK request URLs are not logged; LuSound reports failures through its structured network logs and UI.
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui:1.10.2")
    implementation("androidx.compose.foundation:foundation:1.10.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.jetbrains:annotations:26.0.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.2")
}
