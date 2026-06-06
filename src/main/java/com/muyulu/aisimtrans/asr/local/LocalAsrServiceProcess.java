package com.muyulu.aisimtrans.asr.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.RuntimeConfigService;
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;

@Service
public class LocalAsrServiceProcess {
    private static final Logger log = LoggerFactory.getLogger(LocalAsrServiceProcess.class);
    private static final String SERVICE_VERSION = "local-asr-service-v3";

    private final SimTransProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final SubtitleEventPublisher subtitlePublisher;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile Process process;

    public LocalAsrServiceProcess(
            SimTransProperties properties,
            RuntimeConfigService runtimeConfigService,
            SubtitleEventPublisher subtitlePublisher
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.subtitlePublisher = subtitlePublisher;
    }

    public synchronized void ensureRunning() throws IOException, InterruptedException {
        HealthState health = checkHealth();
        if (health == HealthState.READY) {
            log.info("\u672c\u5730 ASR \u670d\u52a1\u5df2\u5c31\u7eea\uff0c\u7aef\u53e3={}", properties.localAsr().servicePort());
            return;
        }
        if (health == HealthState.WRONG_VERSION) {
            stopStaleServiceOnPort();
        }
        if (process != null && process.isAlive()) {
            log.info("\u7b49\u5f85\u5df2\u6709\u672c\u5730 ASR \u670d\u52a1\u8fdb\u7a0b\u5c31\u7eea\uff0c\u7aef\u53e3={}", properties.localAsr().servicePort());
            waitUntilHealthy();
            return;
        }
        ensureEnvironment();
        log.info("\u5f00\u59cb\u542f\u52a8\u672c\u5730 ASR \u670d\u52a1\uff0c\u7aef\u53e3={}", properties.localAsr().servicePort());
        ProcessBuilder builder = new ProcessBuilder(
                pythonExecutable().toString(),
                Path.of("python", "asr_service.py").toString(),
                "--port",
                String.valueOf(properties.localAsr().servicePort())
        );
        builder.redirectErrorStream(true);
        process = builder.start();
        Thread.ofVirtual().name("local-asr-service-log").start(() -> streamLogs(process));
        waitUntilHealthy();
        log.info("\u672c\u5730 ASR \u670d\u52a1\u542f\u52a8\u6210\u529f\uff0c\u7aef\u53e3={}", properties.localAsr().servicePort());
    }

    public synchronized void stop() {
        Process local = process;
        if (local != null && local.isAlive()) {
            log.info("\u6b63\u5728\u505c\u6b62\u672c\u5730 ASR \u670d\u52a1\u8fdb\u7a0b");
            local.destroy();
        }
        process = null;
        stopLocalServiceOnPort();
    }

    private void ensureEnvironment() throws IOException, InterruptedException {
        if (!installing.compareAndSet(false, true)) {
            while (installing.get()) {
                Thread.sleep(250);
            }
            return;
        }
        try {
            Path python = pythonExecutable();
            if (Files.exists(python) && !isSupportedPython(python)) {
                log.info("\u5df2\u6709 ASR \u865a\u62df\u73af\u5883 Python \u7248\u672c\u4e0d\u6ee1\u8db3\u8981\u6c42\uff0c\u51c6\u5907\u91cd\u5efa .venv-asr");
                deleteVirtualEnvironment();
                python = pythonExecutable();
            }
            if (!Files.exists(python)) {
                log.info("\u5f00\u59cb\u521b\u5efa ASR Python \u865a\u62df\u73af\u5883\uff0c\u547d\u4ee4={}", String.join(" ", venvCreateCommand()));
                run(venvCreateCommand(), Duration.ofMinutes(5));
                python = pythonExecutable();
            }
            if (!Files.exists(python)) {
                throw new IllegalStateException("Python virtual environment was created, but interpreter was not found at " + python);
            }
            log.info("\u5f00\u59cb\u5b89\u88c5\u6216\u5347\u7ea7 ASR Python \u6253\u5305\u5de5\u5177");
            run(List.of(python.toString(), "-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel"), Duration.ofMinutes(10));
            log.info("\u5f00\u59cb\u5b89\u88c5 ASR \u57fa\u7840 Python \u4f9d\u8d56\uff0c\u6587\u4ef6=python/requirements.txt");
            run(List.of(python.toString(), "-m", "pip", "install", "-r", Path.of("python", "requirements.txt").toString()), Duration.ofMinutes(20));
            if (needsFunAsrDependencies()) {
                log.info("\u5f00\u59cb\u5b89\u88c5 FunASR/SenseVoice \u53ef\u9009\u4f9d\u8d56\uff0c\u6587\u4ef6=python/requirements-funasr.txt");
                run(List.of(python.toString(), "-m", "pip", "install", "-r", Path.of("python", "requirements-funasr.txt").toString()), Duration.ofMinutes(20));
            }
            if (needsAnimeWhisperDependencies()) {
                log.info("开始安装 Anime Whisper 可选依赖，文件=python/requirements-anime.txt");
                run(List.of(python.toString(), "-m", "pip", "install", "-r", Path.of("python", "requirements-anime.txt").toString()), Duration.ofMinutes(30));
            }
        } finally {
            installing.set(false);
        }
    }

    private void waitUntilHealthy() throws InterruptedException {
        long deadline = System.nanoTime() + properties.localAsr().startupTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            HealthState health = checkHealth();
            if (health == HealthState.READY) {
                return;
            }
            if (health == HealthState.WRONG_VERSION) {
                stopStaleServiceOnPort();
            }
            Thread.sleep(500);
        }
        log.error("\u672c\u5730 ASR \u670d\u52a1\u672a\u5728\u8d85\u65f6\u65f6\u95f4\u5185\u5c31\u7eea\uff0c\u7aef\u53e3={}", properties.localAsr().servicePort());
        throw new IllegalStateException("Local ASR service did not become healthy on port " + properties.localAsr().servicePort());
    }

    private HealthState checkHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder(serviceUri("/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return HealthState.UNAVAILABLE;
            }
            boolean versionMatches = response.body().contains("\"version\":\"" + SERVICE_VERSION + "\"");
            if (!versionMatches) {
                log.warn("\u68c0\u6d4b\u5230\u672c\u5730 ASR \u670d\u52a1\u5065\u5eb7\u68c0\u67e5\u7248\u672c\u4e0d\u5339\u914d\uff0c\u671f\u671b={}\uff0c\u5b9e\u9645={}\uff0c\u7aef\u53e3={}",
                        SERVICE_VERSION, response.body(), properties.localAsr().servicePort());
                return HealthState.WRONG_VERSION;
            }
            return HealthState.READY;
        } catch (Exception ex) {
            return HealthState.UNAVAILABLE;
        }
    }

    private void stopStaleServiceOnPort() throws InterruptedException {
        if (process != null && process.isAlive()) {
            log.info("\u505c\u6b62\u7248\u672c\u4e0d\u5339\u914d\u7684\u672c\u5730 ASR \u670d\u52a1\u8fdb\u7a0b\uff0cPID={}", process.pid());
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            process = null;
        }
        String port = String.valueOf(properties.localAsr().servicePort());
        stopLocalServiceOnPort();
        Thread.sleep(500);
    }

    private void stopLocalServiceOnPort() {
        String port = String.valueOf(properties.localAsr().servicePort());
        ProcessHandle.allProcesses()
                .filter(handle -> handle.info().commandLine().map(command -> isLocalAsrCommand(command, port)).orElse(false))
                .forEach(handle -> {
                    log.info("\u505c\u6b62\u5360\u7528\u7aef\u53e3\u7684\u672c\u5730 ASR \u670d\u52a1\uff0cPID={}", handle.pid());
                    handle.destroyForcibly();
                });
    }

    private boolean isLocalAsrCommand(String commandLine, String port) {
        return commandLine.contains("asr_service.py")
                && commandLine.contains(port)
                && commandLine.contains("--port");
    }

    private enum HealthState {
        READY,
        UNAVAILABLE,
        WRONG_VERSION
    }

    private URI serviceUri(String path) {
        return URI.create("http://127.0.0.1:" + properties.localAsr().servicePort() + path);
    }

    private Path pythonExecutable() {
        Path scripts = Path.of(".venv-asr", "Scripts", "python.exe");
        if (isWindows() || Files.exists(scripts)) {
            return scripts;
        }
        return Path.of(".venv-asr", "bin", "python");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean needsFunAsrDependencies() {
        String engine = runtimeConfigService.current().asrEngine();
        return "sensevoice".equals(engine) || "funasr-nano".equals(engine);
    }

    private boolean needsAnimeWhisperDependencies() {
        return "anime-whisper".equals(runtimeConfigService.current().asrEngine());
    }

    private List<String> venvCreateCommand() {
        String python = properties.localAsr().python();
        if (isWindows() && "py".equalsIgnoreCase(python)) {
            return List.of("py", "-3.13", "-m", "venv", ".venv-asr");
        }
        return List.of(python, "-m", "venv", ".venv-asr");
    }

    private boolean isSupportedPython(Path python) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                python.toString(),
                "-c",
                "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
        );
        Process versionProcess = builder.start();
        return versionProcess.waitFor(10, TimeUnit.SECONDS) && versionProcess.exitValue() == 0;
    }

    private void deleteVirtualEnvironment() throws IOException {
        Path venv = Path.of(".venv-asr").toAbsolutePath().normalize();
        if (!Files.exists(venv)) {
            return;
        }
        Path workspace = Path.of("").toAbsolutePath().normalize();
        if (!venv.startsWith(workspace) || !venv.getFileName().toString().equals(".venv-asr")) {
            throw new IllegalStateException("\u62d2\u7edd\u5220\u9664\u5f02\u5e38\u865a\u62df\u73af\u5883\u8def\u5f84: " + venv);
        }
        try (var stream = Files.walk(venv)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(true);
        log.info("\u6267\u884c\u547d\u4ee4\uff1a{}", String.join(" ", command));
        Process commandProcess = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> collectOutput(commandProcess, output));
        boolean finished = commandProcess.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        reader.join(Duration.ofSeconds(1));
        if (!finished) {
            commandProcess.destroyForcibly();
            log.error("\u547d\u4ee4\u6267\u884c\u8d85\u65f6\uff1a{}", String.join(" ", command));
            throw new IllegalStateException("Command timed out: " + String.join(" ", command));
        }
        if (commandProcess.exitValue() != 0) {
            log.error("\u547d\u4ee4\u6267\u884c\u5931\u8d25\uff0c\u9000\u51fa\u7801={}\uff0c\u547d\u4ee4={}", commandProcess.exitValue(), String.join(" ", command));
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
        log.info("\u547d\u4ee4\u6267\u884c\u6210\u529f\uff1a{}", String.join(" ", command));
    }

    private void collectOutput(Process commandProcess, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(commandProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (IOException ignored) {
        }
    }

    private void streamLogs(Process localProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(localProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                subtitlePublisher.publish(SubtitleEvent.status("local-asr: " + line));
            }
        } catch (IOException ignored) {
        }
    }
}
