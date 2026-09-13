# dyparse

> 抖音（Douyin）作品解析与下载工具 —— Android 客户端（Kotlin / Jetpack Compose）+ PHP 服务端。

[![Build](https://github.com/kd64i/dyparse/actions/workflows/build.yml/badge.svg)](https://github.com/kd64i/dyparse/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

> [!WARNING]
> **本项目仅供学习 Android 开发、网络协议分析与爬虫技术研究使用。**
> 请勿用于任何商业用途或侵犯他人著作权、平台服务条款的行为。使用前请务必阅读 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 目录

- [功能特性](#功能特性)
- [架构概览](#架构概览)
- [给使用者：安装与配置](#给使用者安装与配置)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [服务端部署](#服务端部署)
- [发布正式包](#发布正式包)
- [项目结构](#项目结构)
- [测试](#测试)
- [安全与合规](#安全与合规)
- [第三方组件](#第三方组件)
- [许可证](#许可证)

---

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 单条解析 | 粘贴分享口令 / 分享链接 / 作品 ID，解析视频或图集 |
| 批量解析 | 一次提交多条链接，并发解析并汇总结果 |
| 作者主页抓取 | 通过服务端携带登录态拉取作者完整作品列表（突破客户端匿名可见条数限制） |
| 画质选择 | 支持「原画质」与「最高画质」（按 `bit_rate` 最高档选择）保存 |
| 实况照片 | 识别并预览 / 保存实况图（Live Photo）的视频轨与图片轨 |
| 解析历史 | 基于 Room 本地持久化，支持查看与重新保存 |
| 液态玻璃 UI | 基于 [Backdrop](https://github.com/Kyant0/Backdrop) 的毛玻璃 / 液态动效界面 |
| 请求签名 | 客户端实现 `X-Bogus`，服务端实现 `a_bogus`，用于通过平台的请求校验 |
| 安全加固 | 原生层（C++）反调试 / 反注入 / 环境检测，见 `SecurityGuard.kt` |

## 架构概览

```
┌──────────────────────────────┐
│  Android App (Kotlin/Compose)│
│  · UI: MainActivity + ui/    │
│  · 状态: ParserViewModel     │
│  · 存储: Room (HistoryDatabase)
└───────────┬──────────────────┘
            │  HTTPS + X-Token / X-Time / X-Sign (HMAC-SHA256)
            ▼
┌──────────────────────────────┐
│  PHP API (server/)           │
│  · data.php      单条解析     │
│  · author_list.php 作者列表   │
│  · abogus.php    a_bogus 签名 │
└───────────┬──────────────────┘
            │  携带登录 Cookie
            ▼
        抖音开放接口 / 分享页
```

解析逻辑**全部在服务端**：App 只负责调用自己的 PHP API，不在本地直连抖音。服务端持有登录 Cookie，负责 a_bogus 签名、原画质/最高画质地址解析。

> ⚠️ **服务端是必需组件，不是可选项。**
> 单条解析、保存、画质切换都直接依赖服务端接口，**没有服务端 App 无法工作**。
> 仅「作者主页作品列表」在服务端不可用时会回落到本地 WebView 链路，但结果不完整。
> 也就是说：**想用这个 App，你必须先按 [`server/README.md`](server/README.md) 部署自己的服务端。**

## 给使用者：安装与配置

> 只想用这个 App、不想写代码的话，看这一节就够了。

**前提：你需要有自己的服务端。** 本项目的解析逻辑全部在服务端（原因见[架构概览](#架构概览)），而服务端要配置一个真人抖音账号的 Cookie —— 作者**不会也无法**把自己的服务端开放给你用。

### 步骤

1. **拿到 APK**，三选一：
   - 到 [Releases](https://github.com/kd64i/dyparse/releases) 下载 `app-release.apk`；
   - 从 Actions 拿 debug 包：最近一次成功的 **Build** → 页面底部 **Artifacts** → `app-debug`（需登录 GitHub）；
   - 自己构建，见[快速开始](#快速开始)。
2. **部署服务端**：按 [`server/README.md`](server/README.md) 把 `data.php`、`author_list.php`、`abogus.php`、`config.php` 上传到你的虚拟主机。
3. **在 App 里配置**：打开 App → **设置 → 服务器配置** → 填入解析接口地址、作者列表接口地址、API Token、HMAC 密钥 → 点 **测试连接** → 通过后点 **保存**。

「测试连接」会做两件事：

- 访问 `data.php?diag=1`（服务端不鉴权）→ 确认地址可达、确实是本项目的服务端，并告诉你服务端**有没有配好抖音 Cookie**；
- 带签名探测一次 → 告诉你 **Token 与 HMAC 密钥是否正确**。

### 常见提示对照

| App 提示 | 原因 |
| --- | --- |
| 未配置服务器 | 还没在 App 里填地址，或填的仍是默认占位地址 `your-server.example.com` |
| 鉴权失败：API Token 与服务端不一致 | App 里填的 Token ≠ `server/config.php` 的 `API_TOKEN` |
| 鉴权失败：HMAC 密钥与服务端不一致 | 同上，对应 `API_HMAC_KEY` |
| 鉴权失败：服务器与手机时间相差超过 5 分钟 | 服务端校验了时间戳防重放，校准服务器或手机时间 |
| 抖音 Cookie：未配置 | 服务端 `DOUYIN_COOKIE` 为空。单条解析可用，但批量解析 / 原画质 / 最高画质会受限 |

> 在 App 内填写的值保存在本机 SharedPreferences，**优先于**构建时注入的默认值。想改回默认值，在对话框里点「恢复默认」。

## 快速开始

### 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17 或更高（`gradle/gradle-daemon-jvm.properties` 固定为 21，缺失时 Gradle 会自动下载） |
| Android SDK | compileSdk 36（Android 16） |
| Android NDK | 需与 CMake 3.22.1 配套（用于 `app/src/main/cpp`） |
| Gradle | 使用仓库自带 wrapper，无需单独安装 |
| 最低运行版本 | Android 7.0（API 24），仅构建 `arm64-v8a` |

### 构建

```bash
git clone https://github.com/kd64i/dyparse.git
cd dyparse

# 1) 配置本地参数（首次必须，否则只有占位服务器地址）
cp local.properties.example local.properties
# 编辑 local.properties：填写 sdk.dir，以及服务器地址/密钥（不打正式包的话签名可留空）

# 2) 构建 Debug 包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3) 运行单元测试
./gradlew test
```

在 Android Studio 中直接 `Open` 项目根目录即可，IDE 会自动生成 `local.properties` 中的 `sdk.dir`。

## 配置说明

所有敏感与易变配置都通过 **`local.properties`（已被 `.gitignore` 忽略）**、Gradle 属性或环境变量注入，源码中只保留占位值。查找优先级为：

```
Gradle 属性  >  local.properties  >  环境变量  >  源码中的占位默认值
```

### Release 签名（可选）

| 键 | 说明 |
| --- | --- |
| `RELEASE_STORE_FILE` | 密钥库路径，建议放在项目目录之外 |
| `RELEASE_STORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

未配置时 `assembleRelease` 会因签名缺失而失败，`assembleDebug` 不受影响。

### 服务器解析 API（**必需**）

| 键 | 对应服务端常量 |
| --- | --- |
| `SERVER_API_BASE` | `data.php` 的完整地址 |
| `SERVER_AUTHOR_API_BASE` | `author_list.php` 的完整地址 |
| `SERVER_API_TOKEN` | `API_TOKEN` |
| `SERVER_HMAC_KEY` | `API_HMAC_KEY` |

这些值会被写入 `BuildConfig`（`app/build.gradle.kts` 中的 `buildConfigField`），由 `ServerApiClient` / `ServerAuthorClient` 读取。

注意两点：

- 它们只是**构建时的默认值**。使用者可以在 App 里「设置 → 服务器配置」覆盖（存本机 SharedPreferences，优先级更高），所以**分发的 APK 可以只带占位地址**，不必泄露你的服务器。
- **不要把真实 token 写进源码或提交到仓库**；CI 中请使用 GitHub Actions Secrets。

### 依赖仓库来源（可选）

默认使用**官方源**（`google()` / `mavenCentral()` / `gradlePluginPortal()`），阿里云镜像仅作兜底，以保证 GitHub Actions 与海外用户解析稳定。

如果你在国内、访问 `dl.google.com` 困难，可以切回「阿里云优先」。在你**自己机器**的 `~/.gradle/gradle.properties` 里加一行即可（**不要**写进本仓库的 `gradle.properties`，否则 CI 也会跟着走镜像）：

```properties
systemProp.dyparse.cnMirrors=true
```

或构建时设置环境变量 `DY_PARSE_CN_MIRRORS=true`。

## 服务端部署

见 [`server/README.md`](server/README.md)。要点：

1. 上传 `data.php`、`author_list.php`、`abogus.php` 与你的 `config.php` 到同一目录；
2. `config.php` 由 `server/config.example.php` 复制而来，填入强随机 `API_TOKEN`、`API_HMAC_KEY` 和你自己的抖音 Cookie；
3. `config.php` 已被 `.gitignore` 忽略，**永远不要提交**。

> 你抖音账号的完整 Cookie 等同于账号凭据。一旦泄露，攻击者可直接接管账号。若曾误提交，请立即在抖音退出登录以作废会话。

## 发布正式包

### 本地打包

在 `local.properties` 里配置签名（这些键**不能**提交，密钥库也**必须**放在项目目录之外）：

```properties
RELEASE_STORE_FILE=D:/path/to/your-release.jks
RELEASE_STORE_PASSWORD=你的库口令
RELEASE_KEY_ALIAS=你的别名
RELEASE_KEY_PASSWORD=你的密钥口令
```

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

未配置签名时 `assembleRelease` 会失败，`assembleDebug` 不受影响。

> ⚠️ **本地构建出的 APK 不要公开分发。**
> 如果你在 `local.properties` 里填了真实的 `SERVER_API_BASE` / `SERVER_API_TOKEN` / `SERVER_HMAC_KEY`，
> 这些值会被编译进 `BuildConfig`，任何人拿到 APK 都能从 `classes.dex` 里直接读出来
> （实测：`unzip` 后 `strings classes.dex | findstr 636366` 就能看到服务器域名）。
> 本地包只适合自己用；**对外分发请使用 CI 构建的产物**（CI 里没有这些 Secret，包内只有占位地址，
> 使用者需要自己在「设置 → 服务器配置」里填）。

### 打 tag 自动发布（推荐）

推送 `v*` 形式的 tag 会触发 [`.github/workflows/release.yml`](.github/workflows/release.yml)：跑测试 → 用 Secrets 里的密钥库签名打包 → 校验签名 → 自动创建 GitHub Release 并附上 APK。

```bash
# tag 建议与 app/build.gradle.kts 的 versionName 一致（当前 4.2 → v4.2）
git tag v4.2 && git push origin v4.2
```

**一次性配置**：仓库 → Settings → Secrets and variables → Actions → New repository secret，添加 4 个：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 密钥库文件的 Base64 |
| `RELEASE_STORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥口令 |

生成 Base64（Windows PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\path\to\your-release.jks")) | Set-Clipboard
```

macOS / Linux：

```bash
base64 -w0 your-release.jks
```

> ⚠️ **密钥库和口令一旦泄露，任何人都能冒名发布你的 App 更新。** 请只放在 GitHub Secrets 和你的本机，不要放进仓库、不要贴进 Issue。
>
> ⚠️ 发布前请确认你已阅读 [DISCLAIMER.md](DISCLAIMER.md)：分发二进制比只提供源码承担更高的法律风险，请自行评估。

## 项目结构

```
.
├── app/
│   ├── build.gradle.kts               # 模块配置、BuildConfig 注入、签名
│   └── src/
│       ├── main/
│       │   ├── cpp/                   # 原生加固：反调试 / 反注入
│       │   ├── java/com/jn/dyparse/
│       │   │   ├── MainActivity.kt            # 入口 Activity
│       │   │   ├── ParserViewModel.kt         # 核心状态机
│       │   │   ├── ServerApiClient.kt         # 服务端单条解析 + 连接测试
│       │   │   ├── ServerAuthorClient.kt      # 服务端作者列表
│       │   │   ├── ServerConfigStore.kt       # 服务器配置（App 内可改，优先于 BuildConfig）
│       │   │   ├── AuthorBatchManager.kt      # 批量解析调度
│       │   │   ├── DouyinABogusSigner.kt      # X-Bogus 签名
│       │   │   ├── DouyinAuthStore.kt         # Cookie 登录态管理
│       │   │   ├── SecurityGuard.kt           # 加固检测
│       │   │   ├── data/                      # Room 实体、仓库、解析结果模型
│       │   │   └── ui/                        # Compose 页面与组件
│       │   └── res/
│       └── test/                      # 单元测试
├── .github/workflows/
│   ├── build.yml                      # push/PR 时跑测试并产出 debug APK
│   └── release.yml                    # 打 v* tag 时签名打包并发 Release
├── server/                            # PHP 解析 API（含部署文档）
├── gradle/libs.versions.toml          # 版本目录
├── local.properties.example           # 本地配置模板
├── DISCLAIMER.md                      # 免责声明（务必阅读）
└── LICENSE                            # GPL-3.0
```

## 测试

```bash
./gradlew test          # JVM 单元测试
./gradlew connectedAndroidTest   # 需要连接设备 / 模拟器
```

现有单元测试覆盖：解析结果字段映射、图集媒体解析、下载地址解析、作者批量匹配、风控校验识别、服务器地址规范化。

## 安全与合规

- 仓库中**不包含**任何真实凭据：Cookie、token、HMAC 密钥、签名库与密码均已通过 `.gitignore` + 构建期注入隔离。
- 如果你 fork 本项目并部署服务端，请遵守当地法律法规与平台条款，自行承担全部责任。
- 发现安全问题请通过 Issue 或私下联系维护者，不要公开可利用细节。

## 第三方组件

| 组件 | 许可证 |
| --- | --- |
| AndroidX / Jetpack Compose / Room / Media3 | Apache-2.0 |
| OkHttp、Gson | Apache-2.0 |
| Coil | Apache-2.0 |
| [Backdrop](https://github.com/Kyant0/Backdrop)、[Shapes](https://github.com/Kyant0/Shapes) | Apache-2.0 |

以上均与 GPL-3.0 兼容。若你在自己的分支中引入了其他库，请自行补充署名与许可证信息。

## 许可证

本项目基于 **GNU General Public License v3.0** 发布，详见 [LICENSE](LICENSE)。

你可以自由使用、修改、分发本项目，但**任何衍生作品必须以同样的 GPL-3.0 许可证开源**并提供完整源码。
