pluginManagement {
    repositories {
        // KuiklyBase 的定制 Kotlin、协程、序列化制品只在这个源里。
        // 官方 Kotlin 至今没有 ohosArm64 目标，这里必须用定制版本。
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
        mavenCentral()
    }
}

rootProject.name = "ComfyPilotOhos"

include(":shared-ohos")
