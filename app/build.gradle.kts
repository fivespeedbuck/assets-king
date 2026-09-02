import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val formalSigningPropertiesFile = rootProject.file("release-signing/keystore.properties")
val formalSigningProperties = Properties().apply {
    if (formalSigningPropertiesFile.exists()) {
        formalSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.assetsking.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.assetsking.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.1.15"
    }

    buildTypes {
        debug {
        }
        release {
            if (formalSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.create("formalRelease") {
                    storeFile = rootProject.file(formalSigningProperties.getProperty("storeFile"))
                    storePassword = formalSigningProperties.getProperty("storePassword")
                    keyAlias = formalSigningProperties.getProperty("keyAlias")
                    keyPassword = formalSigningProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // 检查更新需读取 BuildConfig.VERSION_NAME（REQ 设置§13）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.enableUnitTest = false
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-ledger"))
    implementation(project(":core-database"))
    implementation(project(":core-ui"))
    implementation(project(":core-usecase"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test"))
    // JVM 单测需提供 Android org.json 的真实实现；不打入正式 APK。
    testImplementation("org.json:json:20240303")
}
