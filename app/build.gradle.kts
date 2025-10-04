plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

// 读取签名属性需在 android 块外创建（Kotlin DSL 推荐）
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        load(signingPropsFile.inputStream())
    }
}

android {
    namespace = "com.l2dchat"
    compileSdk = 36

    defaultConfig {
    applicationId = "com.l2dchat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (!signingPropsFile.exists()) {
                // 如果缺少签名文件，给出清晰错误，防止构建一个未签名的 release
                throw GradleException("缺少 signing.properties，请创建并填写签名信息后再构建 release")
            }
            val storePath = signingProps.getProperty("storeFile")
                ?: throw GradleException("signing.properties 缺少 storeFile")
            storeFile = rootProject.file(storePath)
            storePassword = signingProps.getProperty("storePassword")
                ?: throw GradleException("signing.properties 缺少 storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
                ?: throw GradleException("signing.properties 缺少 keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
                ?: throw GradleException("signing.properties 缺少 keyPassword")
        }
    }

    buildTypes {
        release {
            // 打开混淆与资源压缩（如不需要可改为 false）
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 保持 debug 可读性
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.gson)  // 添加Gson依赖
    implementation(libs.ucrop)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(files("libs/Live2DCubismCore.aar"))
    implementation(project(":framework"))
}
