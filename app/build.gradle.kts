import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val recoverySigningPropertiesFile = rootProject.file("release-signing/keystore.properties")
val recoverySigningProperties = Properties().apply {
    if (recoverySigningPropertiesFile.exists()) {
        recoverySigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.assetsking.app"
    compileSdk = 35

    defaultConfig {
        // 恢复分支的所有变体都必须与正式包隔离；Release 也不得产出 com.assetsking.app。
        applicationId = "com.assetsking.app.recovery"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-recovery"
    }

    buildTypes {
        debug {
            // 真机验收版与用户现有安装并存，避免 UI/迁移回归误触正式账本数据。
        }
        release {
            if (recoverySigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.create("recoveryRelease") {
                    storeFile = rootProject.file(recoverySigningProperties.getProperty("storeFile"))
                    storePassword = recoverySigningProperties.getProperty("storePassword")
                    keyAlias = recoverySigningProperties.getProperty("keyAlias")
                    keyPassword = recoverySigningProperties.getProperty("keyPassword")
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
}
