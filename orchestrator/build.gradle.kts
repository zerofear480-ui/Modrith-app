plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.modrith.orchestrator"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":downloader"))
    implementation(project(":filesystem"))
    implementation(project(":installer"))
    implementation(project(":launcher"))
    implementation(project(":models"))
    implementation(project(":parser"))
    implementation(project(":resolver"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
