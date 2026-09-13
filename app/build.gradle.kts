import java.util.Properties

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)

/**
 * 读取服务器解析 API 的配置（地址 / token / HMAC 密钥）。
 *
 * 查找顺序：gradle 属性 -> local.properties -> 环境变量 -> 默认占位值。
 * 真实值只应保存在 local.properties（已被 .gitignore 忽略）或 CI Secrets 中，
 * 绝不能写进源码或提交到仓库。
 */
fun serverConfig(name: String, default: String): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: default

/** 转义成可以放进 buildConfigField 的 Kotlin 字符串字面量 */
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val defaultServerApiBase = "https://your-server.example.com/api/data.php"
val defaultServerAuthorApiBase = "https://your-server.example.com/api/author_list.php"
val defaultServerApiToken = "dyparse_server_token_change_me"
val defaultServerHmacKey = "dyparse_hmac_change_me"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    signingConfigs {
        create("release") {
            signingProperty("RELEASE_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingProperty("RELEASE_STORE_PASSWORD")
            keyAlias = signingProperty("RELEASE_KEY_ALIAS")
            keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
        }
    }
    namespace = "com.jn.dyparse"
    compileSdk = 36
    // 固定 NDK 版本，保证本地与 CI 构建一致（CI 中按此版本安装）
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.jn.dyparse"
        minSdk = 24
        targetSdk = 34
        versionCode = 42
        versionName = "4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 服务器解析 API（真实值请写在 local.properties 或环境变量里，不要提交）
        buildConfigField(
            "String", "SERVER_API_BASE",
            buildConfigString(serverConfig("SERVER_API_BASE", defaultServerApiBase))
        )
        buildConfigField(
            "String", "SERVER_AUTHOR_API_BASE",
            buildConfigString(serverConfig("SERVER_AUTHOR_API_BASE", defaultServerAuthorApiBase))
        )
        buildConfigField(
            "String", "SERVER_API_TOKEN",
            buildConfigString(serverConfig("SERVER_API_TOKEN", defaultServerApiToken))
        )
        buildConfigField(
            "String", "SERVER_HMAC_KEY",
            buildConfigString(serverConfig("SERVER_HMAC_KEY", defaultServerHmacKey))
        )

        ndk {
            // 增加 ABI 支持，防止因架构不匹配导致的闪退
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.media3:media3-database:1.2.0")
    implementation("androidx.media3:media3-datasource:1.2.0")
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Backdrop is the liquid-glass effect library used by the reference project.
    implementation("io.github.kyant0:backdrop-android:2.0.0-alpha03")
    implementation("io.github.kyant0:shapes-android:1.2.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
