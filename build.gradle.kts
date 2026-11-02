plugins {
    id("com.android.library") version "9.2.1"
    id("com.google.devtools.ksp") version "2.3.12"
}

android {
    namespace = "dev.checkout.talk"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.21")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.sqlite:sqlite-framework:2.6.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17-beta-2")
}
