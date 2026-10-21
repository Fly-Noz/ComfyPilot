plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    // jvm 目标只用于跑共享层单元测试，不产出产品代码
    jvm()

    // 鸿蒙侧不在本构建里：官方 Kotlin 没有 ohosArm64 目标，
    // 共享层由 ohos/ 下的独立构建（KuiklyBase 定制 Kotlin）编成 .so，详见 ohos/README.md。

    androidLibrary {
        namespace = "com.comfypilot.core"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.okhttp)
            implementation(libs.okhttp.logging)
        }
    }
}
