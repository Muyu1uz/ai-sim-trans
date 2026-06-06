package com.muyulu.aisimtrans.asr.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.RuntimeConfigService;

class LocalAsrServiceProcessTests {
    @Test
    void usesWindowsVirtualEnvironmentPythonPathOnWindows() throws Exception {
        SimTransProperties properties = properties();
        LocalAsrServiceProcess process = new LocalAsrServiceProcess(properties, new RuntimeConfigService(properties), event -> {
        });

        Object path = invoke(process, "pythonExecutable");

        assertThat(path.toString()).isEqualTo(".venv-asr\\Scripts\\python.exe");
    }

    @Test
    void usesPyLauncherForWindowsVenvCreation() throws Exception {
        SimTransProperties properties = properties();
        LocalAsrServiceProcess process = new LocalAsrServiceProcess(properties, new RuntimeConfigService(properties), event -> {
        });

        Object command = invoke(process, "venvCreateCommand");

        assertThat(command).asList().containsExactly("py", "-3.13", "-m", "venv", ".venv-asr");
    }

    private SimTransProperties properties() {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-large-v3", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }

    private Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
