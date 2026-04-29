# 🤖 RainyLLM (雨晴LLM)

> *"在手机上跑 Gemma，开放 OpenAI 兼容 API"* 🐱☁️

一个纯离线的 Android 本地 LLM 推理服务器。集成 Google LiteRT-LM 引擎运行 Gemma 模型，通过 NanoHTTPd 在 `127.0.0.1` 广播 OpenAI 兼容 API，供 ChatBox、Open WebUI 等第三方 AI 客户端调用。

![Release](https://github.com/CATMIAOZHI/RainyLLM/actions/workflows/release.yml/badge.svg)

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🧠 **本地推理** | 基于 Google LiteRT-LM，支持 Gemma4-E2B / Gemma4-E4B / Gemma3-1B |
| 🌐 **OpenAI 兼容 API** | `/v1/chat/completions` · `/v1/models` · `/health` |
| 📡 **SSE 流式输出** | 原生支持 `stream: true`，逐 token 流式推送到客户端 |
| 🖼️ **多模态** | 支持图片 (ImageBytes/ImageFile) + 音频 (AudioBytes) 输入 |
| 🎛️ **GPU 加速** | CPU / GPU 后端可切换，GPU prefill 可达 3808 tk/s |
| 🔒 **纯本地 · 零联网** | 127.0.0.1 绑定，不暴露到局域网，隐私安全 |
| 📊 **实时统计** | 请求日志、Token 用量图表、引擎诊断面板 |
| 🎨 **Material Design 3** | Jetpack Compose 构建，科技蓝紫色调 |
| 🔌 **后台保活** | Foreground Service + WakeLock + 通知栏常驻 |

---

## 📦 下载

前往 [Releases](https://github.com/CATMIAOZHI/RainyLLM/releases) 下载最新 APK。

> ⚠️ 模型文件需在 App 内单独下载（Gemma4-E2B 约 2.58GB），不包含在 APK 中。

---

## 🏗️ 技术架构

```
┌──────────────────────────────────────────────────┐
│                  Android App                       │
│                                                    │
│  ┌──────────────────────────────────────────────┐ │
│  │          Compose UI（5 标签页）               │ │
│  │  主控台 · 模型管理 · 聊天测试 · 性能 · 设置  │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │          HTTP Server (NanoHTTPd 2.3.1)        │ │
│  │  POST /v1/chat/completions  ← OpenAI 兼容    │ │
│  │  GET  /v1/models            ← 模型列表       │ │
│  │  GET  /health               ← 健康检查       │ │
│  │  (支持 SSE 流式 + 多模态)                    │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │      推理引擎 (LiteRT-LM Kotlin API)          │ │
│  │  Engine → Conversation → sendMessageAsync()  │ │
│  │  · GPU 加速（3808 tk/s prefill）             │ │
│  │  · Kotlin Flow 流式输出                       │ │
│  └────────────────────┬─────────────────────────┘ │
│                       │                            │
│  ┌────────────────────▼─────────────────────────┐ │
│  │    模型存储 + 下载管理（DownloadManager）     │ │
│  │  · .litertlm 模型文件                          │ │
│  │  · HuggingFace LiteRT Community 下载          │ │
│  │  · SHA256 校验 · 断点续传                     │ │
│  └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

---

## 📁 项目结构

```
RainyLLM/
├── app/src/main/java/com/rainyllm/app/
│   ├── MainActivity.kt                  # 唯一 Activity（Edge-to-Edge + 5 标签页导航）
│   ├── RainyLLMApp.kt                   # Application 类
│   │
│   ├── engine/                          # 推理引擎封装
│   │   ├── LlmEngine.kt                 # LiteRT-LM Engine 封装（初始化/同步/流式推理）
│   │   ├── ConversationPool.kt          # 多会话管理（超时自动清理）
│   │   └── TokenEstimator.kt            # Token 计数估算
│   │
│   ├── server/                          # HTTP API 服务
│   │   ├── OpenAIServer.kt              # NanoHTTPd 服务器（路由分发/CORS/SSE）
│   │   ├── RequestParser.kt             # OpenAI 请求体解析
│   │   └── SseFormatter.kt              # SSE 数据帧格式化
│   │
│   ├── model/                           # 模型管理
│   │   ├── ModelInfo.kt                 # 模型元数据（id/size/url/sha256）
│   │   ├── ModelRepository.kt           # 本地模型扫描/切换
│   │   ├── ModelDownloader.kt           # DownloadManager 下载+断点续传
│   │   └── ModelValidator.kt            # SHA256 校验
│   │
│   ├── service/                         # 后台服务
│   │   ├── LlmServerService.kt          # Foreground Service（引擎+服务器保活）
│   │   └── KeepAliveService.kt          # 通知栏保活服务
│   │
│   ├── data/                            # 数据层
│   │   ├── AppPreferences.kt            # DataStore 偏好存储
│   │   └── StatsRepository.kt           # 推理统计记录
│   │
│   └── ui/                              # Compose UI
│       ├── screen/                      # 5 个页面
│       │   ├── DashboardScreen.kt       # 主控台（服务开关/状态/统计）
│       │   ├── ModelManagerScreen.kt    # 模型下载/切换/删除
│       │   ├── ChatTestScreen.kt        # 内置聊天测试页
│       │   ├── PerformanceScreen.kt     # 性能监控页
│       │   └── SettingsScreen.kt        # 端口/后端/采样参数设置
│       ├── component/                   # 可复用组件
│       │   ├── ServerStatusCard.kt      # 服务状态卡片
│       │   ├── ModelDownloadCard.kt     # 下载进度卡片
│       │   ├── TokenStatsChart.kt       # 推理统计图表
│       │   ├── LogViewer.kt             # 请求日志列表
│       │   └── DebugCard.kt             # 诊断面板
│       └── theme/                       # MD3 主题（蓝紫色调）
│
├── gradle/libs.versions.toml            # Version Catalog 依赖管理
├── build.gradle.kts                     # 项目级配置
└── settings.gradle.kts                  # 项目设置
```

---

## 🛠️ 快速开始

### 方式一：Android Studio（推荐）

1. Clone 仓库：`git clone https://github.com/CATMIAOZHI/RainyLLM.git`
2. 用 Android Studio 打开项目
3. 同步 Gradle，连接设备，Run ▶️

### 方式二：命令行

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

### 首次使用

1. 打开 App，进入「模型管理」标签页
2. 下载一个模型（推荐 Gemma4-E2B，约 2.58GB）
3. 切回「主控台」，点击「启动服务」
4. 服务运行在 `http://127.0.0.1:8080`

<details>
<summary>🔧 ARM64 环境说明（非必需）</summary>

Gradle 从 Google Maven 下载的 AAPT2 在 ARM64 Linux 环境下可能无法直接运行。项目内置了 ARM64 aapt2：

```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh
```

脚本会自动替换 SDK build-tools 和 Gradle 缓存中的 aapt2 为 ARM64 版本。

> 来源：[ReVanced/aapt2](https://github.com/ReVanced/aapt2/releases/tag/v1.0.0)
</details>

---

## 🔌 API 使用

### 健康检查

```bash
curl http://127.0.0.1:8080/health
# → {"status":"ok","model":"gemma4-e2b"}
```

### 模型列表

```bash
curl http://127.0.0.1:8080/v1/models
# → {"object":"list","data":[{"id":"gemma4-e2b","object":"model","owned_by":"rainyllm"}]}
```

### 对话（同步）

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}],"stream":false}'
```

### 对话（SSE 流式）

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"写一首诗"}],"stream":true}'
```

### 客户端配置

| 客户端 | 配置方式 |
|--------|----------|
| **ChatBox** | 设置 → 模型提供方 → OpenAI 兼容 → 填入 `http://127.0.0.1:8080` |
| **Open WebUI** | 设置 → 连接 → OpenAI API → 填入 `http://127.0.0.1:8080/v1` |

---

## 📦 依赖管理

项目使用 Gradle Version Catalog (`gradle/libs.versions.toml`) 统一管理依赖。

| 依赖 | 用途 |
|------|------|
| `com.google.ai.edge.litertlm:litertlm-android` | LiteRT-LM 推理引擎 |
| `org.nanohttpd:nanohttpd:2.3.1` | 轻量 HTTP 服务器 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 协程支持 |
| `androidx.compose:compose-bom` | Jetpack Compose BOM |
| `androidx.navigation:navigation-compose` | 页面导航 |
| `androidx.datastore:datastore-preferences` | 偏好存储 |
| `io.coil-kt:coil-compose` | 图片加载 |

---

## 🔒 安全说明

- ✅ 服务器绑定 `127.0.0.1`，**仅限本机访问**
- ✅ `allowBackup="false"`，拒绝应用数据被备份
- ✅ 纯离线运行，**零网络请求**（模型下载除外）
- ✅ 签名密钥由 GitHub Actions 在 CI 中安全生成并发布 Release APK

---

## ⚠️ 注意事项

| 事项 | 说明 |
|------|------|
| 🧠 模型大小 | Gemma4-E2B 约 2.58GB，需充足存储 |
| 📱 内存需求 | 推荐 8GB+ RAM，推理时约占用 2-3GB |
| ⏱️ 冷启动 | `engine.initialize()` 约 10 秒，需异步执行 |
| 🔋 电量 | 持续推理会耗电发热，有空闲超时机制 |
| 📡 纯本地 | 127.0.0.1 不对局域网开放 |

---

## 🐱 关于

RainyLLM 由 [雨晴喵](https://github.com/CATMIAOZHI) 与水晴共同打造，属于「雨晴系列」工具之一：

- [RainyScanner](https://github.com/CATMIAOZHI/RainyScanner) — 不拦截不跳转的 Android 扫码工具
- [Rainy2FA](https://github.com/CATMIAOZHI/Rainy2FA) — 纯本地生物识别保护的 TOTP 验证器
- **RainyLLM** — 本地 LLM 推理服务器（本项目）

---

## 📄 License

MIT License © 2026 Rainy

---

*Made with ☁️ and 🐱 paws*