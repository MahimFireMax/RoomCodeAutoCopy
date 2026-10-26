plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.roomcodeautocopy"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.roomcodeautocopy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
    }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
