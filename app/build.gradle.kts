import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名：keystore.properties 放在仓库根目录（已 gitignore），
// 里面只有 4 行：storeFile / storePassword / keyAlias / keyPassword。
// 文件不存在时 release 构建保持未签名，方便别人克隆后直接 assembleDebug。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "com.comfypilot.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fly.comfypilot.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 没有混淆规则就先不开，避免 release 与 debug 行为不一致。
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // 参数编辑直接用 JsonElement 表达控件值；shared 的 implementation 不传递给 app。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
