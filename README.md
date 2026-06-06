# AI Sim Trans

面向 Windows 系统音频的实时字幕翻译助手。

## 运行模式

### 本地 ASR 模式

默认流程：

```text
WASAPI loopback -> Java 音频队列 -> 本地 VAD -> 本地 ASR 服务
  -> OpenAI 兼容 LLM 翻译 -> /ws/subtitles -> 浏览器字幕
```

默认配置：

- VAD：选择 `silero`，当前 Java 运行路径使用 energy fallback。
- ASR 引擎：`faster-whisper`。
- ASR 模型：`Systran/faster-whisper-large-v3`。
- 模型缓存目录：`./models/`。
- 模型下载源：优先 ModelScope，失败后回退 HuggingFace。
- 翻译模型：`qwen-turbo`。
- 翻译接口：`https://dashscope.aliyuncs.com/compatible-mode/v1`。

应用会创建 `.venv-asr`，安装 `python/requirements.txt`，启动 `python/asr_service.py`，并在首次启动或点击 `Load model` 时加载所选模型。

### DashScope livetranslate 模式

原来的云端实时翻译模式仍然保留：

```text
WASAPI loopback -> DashScope realtime WebSocket -> 翻译字幕
```

在浏览器控制面板中将 `Mode` 切换为 `dashscope-livetranslate` 即可使用。

## 配置

主要配置位于 `src/main/resources/application.yml`。

常用环境变量：

```powershell
$env:DASHSCOPE_API_KEY="..."
$env:SIMTRANS_MODE="local-asr"
$env:LOCAL_ASR_ENGINE="faster-whisper"
$env:LOCAL_ASR_MODEL="Systran/faster-whisper-large-v3"
$env:TRANSLATION_MODEL="qwen-turbo"
```

## 运行

```powershell
.\mvnw.cmd spring-boot:run
```

打开：

```text
http://localhost:8086
```

## 接口

- `POST /api/pipeline/start`
- `POST /api/pipeline/stop`
- `GET /api/pipeline/status`
- `GET /api/audio/devices`
- `GET /api/runtime/options`
- `POST /api/runtime/config`
- `POST /api/runtime/model/load`
- `GET /ws/subtitles`

## Native 音频采集

如需重新构建 WASAPI loopback DLL：

```powershell
cmake -S native/windows-wasapi -B native/windows-wasapi/build
cmake --build native/windows-wasapi/build --config Release
```

将 `LiveTranslateAudio.dll` 放到应用同级目录，或加入 `java.library.path`。
