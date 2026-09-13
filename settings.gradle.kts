/**
 * 依赖仓库来源（详见 README「依赖仓库来源」）
 *
 * 默认：**官方源优先**（google / mavenCentral / gradlePluginPortal），
 * 阿里云镜像仅作兜底 —— GitHub Actions 与海外用户的解析最稳定。
 *
 * 国内网络拉不动 dl.google.com 时，切回「阿里云优先」：
 *   - 环境变量：DY_PARSE_CN_MIRRORS=true
 *   - 或在自己机器的 ~/.gradle/gradle.properties 里加：
 *       systemProp.dyparse.cnMirrors=true
 *     （不要写进本仓库的 gradle.properties，否则 CI 也会跟着走镜像）
 *
 * 注意：pluginManagement / dependencyResolutionManagement 是独立编译的作用域，
 * 不能引用脚本顶层的声明，所以下面的判断在各自块内重复一次。
 */

pluginManagement {
    val useCnMirrors =
        (System.getenv("DY_PARSE_CN_MIRRORS") ?: System.getProperty("dyparse.cnMirrors") ?: "false")
            .toBoolean()
    repositories {
        if (useCnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        if (!useCnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    val useCnMirrors =
        (System.getenv("DY_PARSE_CN_MIRRORS") ?: System.getProperty("dyparse.cnMirrors") ?: "false")
            .toBoolean()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useCnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        if (!useCnMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

rootProject.name = "dyparse"
include(":app")
