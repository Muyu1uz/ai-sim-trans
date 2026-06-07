# AI Sim Trans

AI Sim Trans 是一个面向 Windows 系统音频的实时字幕翻译工具。它通过 WASAPI loopback 捕获电脑正在播放的声音，完成语音识别、翻译、字幕展示，并支持本地 ASR、云端同传、翻译提示词和字幕纠错复核等能力。

## Demo 演示

演示视频：

https://www.bilibili.com/video/BV1tEEt6zEP5/?spm_id_from=333.1387.homepage.video_card.click&vd_source=cbfce558dafffb7b02c21db77066eb98

## 项目结构

```text
.
├── frontend/web/static/              # 浏览器端字幕界面和运行配置面板
├── native/windows-wasapi/            # Windows WASAPI loopback 原生音频采集 DLL
├── python/                           # 本地 ASR Python 服务和依赖清单
├── src/main/java/com/muyulu/aisimtrans/
│   ├── asr/                          # ASR 抽象、DashScope 云端 ASR、本地 ASR 调度
│   ├── audio/                        # 音频块、队列、采集接口和 WASAPI JNA 接入
│   ├── config/                       # Spring 配置、应用配置映射、WebSocket 配置
│   ├── controller/                   # REST API：流水线控制、运行时配置、模型加载
│   ├── correction/                   # 字幕纠错复核请求、结果和 OpenAI-compatible 实现
│   ├── pipeline/                     # 流水线状态 DTO
│   ├── runtime/                      # 运行时配置、配置更新和可选项 DTO
│   ├── service/                      # 主流水线、运行时配置服务、字幕 WebSocket 推送
│   ├── subtitle/                     # 字幕事件模型和事件发布接口
│   ├── translation/                  # 翻译请求、流式增量、上下文记忆和 OpenAI-compatible 翻译
│   └── vad/                          # Energy / Silero / Disabled VAD
├── src/main/resources/application.yml # 默认配置和环境变量入口
├── src/test/java/                    # 单元测试和流水线行为测试
├── pom.xml                           # Maven / Spring Boot 配置
└── mvnw.cmd                          # Windows Maven Wrapper
```

## 项目亮点功能

- 系统音频实时捕获：使用 Windows WASAPI loopback 采集电脑输出音频，不依赖麦克风输入。
- 双运行模式：支持本地 ASR + LLM 翻译，也支持 DashScope 云端实时同传。
- 本地 ASR 服务：Java 自动管理 Python 虚拟环境、依赖安装、模型下载、模型加载和健康检查。
- 多 ASR 引擎：支持 SenseVoice、FunASR Nano、faster-whisper、anime-whisper 等本地引擎。
- VAD 分段：内置 energy VAD，可按最小时长、静音阈值、最长分段控制实时转写。
- OpenAI-compatible 翻译：兼容 DashScope / OpenAI 风格 Chat Completions 接口。
- 翻译 System Prompt：可通过环境变量或前端设置翻译提示词，用于术语、人名、风格约束。
- 字幕纠错复核：在后续上下文出现后，对近期字幕进行高置信度纠错并通过 revision 推送更新。
- Web 字幕界面：浏览器实时展示字幕，支持启动/停止、清空字幕、回到底部、运行时参数配置。

## 前置要求

### 必需

- Windows 10/11
- JDK 21
- PowerShell
- Maven Wrapper 使用仓库内 `mvnw.cmd`，无需单独安装 Maven
- DashScope 或 OpenAI-compatible API Key，用于翻译；云端同传模式还需要 DashScope 实时 ASR 权限

### 本地 ASR 模式额外需要

- Python 3.10、3.11 或 3.12
- 可联网下载 Python 依赖和 ASR 模型
- 推荐 NVIDIA CUDA 环境；如果没有 CUDA，可把 `LOCAL_ASR_DEVICE` 改为 `cpu`，并按模型能力调整 `LOCAL_ASR_COMPUTE_TYPE`

### 原生音频 DLL

项目依赖 `LiveTranslateAudio.dll` 进行 Windows 系统音频采集。如果需要重新构建：

```powershell
cmake -S native/windows-wasapi -B native/windows-wasapi/build
cmake --build native/windows-wasapi/build --config Release
```

将构建出的 `LiveTranslateAudio.dll` 放到应用启动目录，或把 DLL 所在目录加入 `java.library.path` / `PATH`。

## 如何启动

### 1. 配置环境变量

最少需要配置翻译接口 Key：

```powershell
$env:DASHSCOPE_API_KEY="你的 API Key"
```

常用可选配置：

```powershell
# 运行模式：local-asr 或 dashscope-livetranslate
$env:SIMTRANS_MODE="local-asr"

# 本地 ASR 默认使用 SenseVoice
$env:LOCAL_ASR_ENGINE="sensevoice"
$env:LOCAL_ASR_MODEL="iic/SenseVoiceSmall"
$env:LOCAL_ASR_DEVICE="cuda"
$env:LOCAL_ASR_COMPUTE_TYPE="float16"

# OpenAI-compatible 翻译接口
$env:TRANSLATION_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:TRANSLATION_MODEL="qwen-turbo"
$env:TRANSLATION_PROMPT="保持角色名不翻译；固定术语按项目词表翻译。"

# 字幕纠错复核
$env:CORRECTION_ENABLED="true"
```

### 2. 启动应用

```powershell
.\mvnw.cmd spring-boot:run
```

默认端口是 `8086`。启动后打开：

```text
http://localhost:8086
```

### 3. 使用流程

1. 在网页右上角打开设置。
2. 选择运行模式：
   - `local-asr`：本地识别 + OpenAI-compatible 翻译。
   - `dashscope-livetranslate`：DashScope 云端同传。
3. 本地模式下确认 ASR 模型、设备、VAD、翻译模型和 System Prompt。
4. 点击“保存设置”。
5. 本地模式首次使用时点击“加载模型”，等待模型下载和加载完成。
6. 点击“启动”，播放系统音频，浏览器中会实时显示字幕。

## 常用接口

- `POST /api/pipeline/start`：启动实时字幕流水线
- `POST /api/pipeline/stop`：停止流水线
- `GET /api/pipeline/status`：查看流水线状态和诊断信息
- `GET /api/audio/devices`：获取系统音频输出设备
- `GET /api/runtime/options`：获取运行时选项
- `POST /api/runtime/config`：更新运行时配置
- `POST /api/runtime/model/load`：加载本地 ASR 模型
- `GET /ws/subtitles`：字幕 WebSocket 推送
