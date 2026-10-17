import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从 keystore.properties 读取；文件不存在时回退到 debug 签名，保证任何机器都能构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "io.github.tviperf"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.tviperf"
        minSdk = 28          // 预编译 libiperf3.so 由 NDK r28c / API 28 构建，低于 28 会起不来
        targetSdk = 34
        // versionName 重新从 1.0.0 起算（发布历史以这一版为首版）。
        // versionCode 保持递增而不回到 1：这样已安装的旧构建可以直接覆盖升级，
        // 不必先卸载（Android 会拒绝 versionCode 降低的安装包）。
        versionCode = 14
        versionName = "1.0.10"

        // 只打 ARM 两种 ABI。电视 SoC（MT9655）可能是 32 位系统，两个都带上最稳妥。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    // 关键：让 libiperf3.so 在安装时被解压到 nativeLibraryDir。
    // Android 10+ 的 W^X 策略禁止执行应用私有目录里的文件，只有 nativeLibraryDir 例外。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
