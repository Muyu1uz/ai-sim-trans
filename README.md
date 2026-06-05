# AI 同声传译助手

面向 Windows 系统音频的 AI 同声传译助手。项目通过 WASAPI loopback 捕获系统播放声音，将音频流发送到 DashScope/Qwen 实时 ASR，再通过 OpenAI 兼容翻译接口生成中文字幕，并通过 WebSocket 推送到本地字幕页面。

## 运行流程

```text
WASAPI loopback DLL -> JNA -> AudioFrameQueue -> DashScope ASR WebSocket
  -> ASR final 句子 -> OpenAI 兼容流式翻译
  -> /ws/subtitles -> 字幕 UI
```

native DLL 返回 `PCM16 mono 16kHz` 音频块，每块 `512` 个采样点，也就是 32 ms。

第一版只翻译 ASR final 句子。ASR partial 处理和上下文翻译已保留扩展点，但当前运行时流水线不会启用。

## 配置

启动应用前设置环境变量：

```powershell
$env:DASHSCOPE_API_KEY="..."
$env:TRANSLATION_BASE_URL="https://api.deepseek.com/v1"
$env:TRANSLATION_API_KEY="..."
$env:TRANSLATION_MODEL="deepseek-chat"
```

如果使用 Ollama 或本地 vLLM，将 `TRANSLATION_BASE_URL` 指向对应的 OpenAI 兼容 `/v1` 接口即可。

主要配置位于 `src/main/resources/application.yml`：

- `simtrans.audio`：音频采集参数，包括采样率、chunk 大小、队列容量、native DLL 名称。
- `simtrans.asr`：DashScope/Qwen 实时 ASR 配置，包括 WebSocket 地址、模型、语言和 `server_vad`。
- `simtrans.translation`：OpenAI 兼容翻译接口配置，包括 base URL、API Key、模型、目标语言。
- `simtrans.subtitle`：字幕显示参数。

## 构建 native DLL

安装 Visual Studio Build Tools 和 CMake 后执行：

```powershell
cmake -S native/windows-wasapi -B native/windows-wasapi/build
cmake --build native/windows-wasapi/build --config Release
```

构建完成后，将 `LiveTranslateAudio.dll` 放到应用 jar 同级目录，或将 DLL 输出目录加入 `java.library.path`。

## 运行

```powershell
.\mvnw.cmd spring-boot:run
```

打开 `http://localhost:8080`，点击 `Start` 启动流水线。

## 接口

- `POST /api/pipeline/start`
- `POST /api/pipeline/stop`
- `GET /api/pipeline/status`
- `GET /api/audio/devices`
- `ws://localhost:8080/ws/subtitles`

## 第三方依赖与用途

- Java 21：项目运行环境。
- Spring Boot：后端服务、REST API、WebSocket、静态页面托管。
- Spring WebFlux WebClient：调用 OpenAI 兼容流式翻译接口。
- JNA：Java 调用 Windows native WASAPI DLL。
- Windows WASAPI：通过 loopback 捕获系统输出音频。
- DashScope/Qwen Realtime ASR：实时语音识别服务，由服务端 `server_vad` 完成端点检测。
- OpenAI-compatible API：兼容 DeepSeek、Qwen、GPT、Ollama、本地 vLLM 等翻译后端。

## 原创功能部分

- 系统音频到 Java 后端的音频块抽象和实时队列。
- WASAPI loopback native 采集模块。
- JNA 音频采集桥接。
- DashScope 实时 ASR Provider 抽象与实现。
- OpenAI 兼容翻译 Provider 抽象与实现。
- ASR final-only 翻译流水线。
- 字幕事件模型、WebSocket 推送和本地字幕页面。

## 当前版本说明

- 当前版本聚焦第一版 MVP：系统音频采集、流式 ASR、按 final 句子翻译、字幕显示。
- 当前不翻译 ASR partial，也不启用上下文翻译。
- native DLL 需要在安装 CMake 和 Visual Studio Build Tools 的 Windows 环境中单独构建。
