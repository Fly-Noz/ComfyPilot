plugins {
    // 注意：这是 KuiklyBase 的定制 Kotlin，不是官方版本。
    // 官方 Kotlin（本仓库 Android 侧用的 2.4.21）没有 ohosArm64 目标。
    kotlin("multiplatform") version "2.0.21-KBA-010"
    kotlin("plugin.serialization") version "2.0.21-KBA-010"
}

kotlin {
    ohosArm64 {
        binaries {
            sharedLib {
                baseName = "comfypilot_core"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            // 唯一源码来源：与 Android 共用，不复制。
            kotlin.srcDir("../../shared/src/commonMain/kotlin")
            dependencies {
                // 必须是带 ohosArm64 制品的 KBA 版本
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:2.0.21-coroutines-KBA-001")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
        }

        val ohosArm64Main by getting {
            // 平台侧 C ABI（@CName 导出）也放在 shared 下，Android 构建不会包含它。
            kotlin.srcDir("../../shared/src/ohosArm64Main/kotlin")
        }
    }
}
