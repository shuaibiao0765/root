plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.minis.pd2546root"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig {
        applicationId = "com.minis.pd2546root"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "2.2"
    }
    buildTypes {
        release {
            // 用 debug 签名，装得上就行；正式发布换成自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Miuix：GhostLock 同款 HyperOS 风格组件（0.9.4 需要 compileSdk 37 + AGP 9）
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")

    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Shizuku：拿 uid=2000 的 shell
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
