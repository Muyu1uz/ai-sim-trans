package com.muyulu.aisimtrans.asr.local;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.service.RuntimeConfigService;

@Service
public class LocalAsrModelManager {
    private static final Logger log = LoggerFactory.getLogger(LocalAsrModelManager.class);
    private static final String TEMP_CACHE_PREFIX = "._____temp";
    private static final String[] FUNASR_REQUIRED_FILES = {
            "configuration.json",
            "config.yaml",
            "model.pt",
            "chn_jpn_yue_eng_ko_spectok.bpe.model",
            "am.mvn"
    };

    private final SimTransProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final LocalAsrServiceProcess serviceProcess;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LocalAsrModelManager(
            SimTransProperties properties,
            RuntimeConfigService runtimeConfigService,
            LocalAsrServiceProcess serviceProcess,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.serviceProcess = serviceProcess;
        this.objectMapper = objectMapper;
    }

    public synchronized ModelStatus ensureLoaded() {
        RuntimeConfig config = runtimeConfigService.current();
        if (!RuntimeConfigService.MODE_LOCAL_ASR.equals(config.mode())) {
            log.info("\u5f53\u524d\u6a21\u5f0f\u4e0d\u662f\u672c\u5730 ASR\uff0c\u8df3\u8fc7\u672c\u5730\u6a21\u578b\u52a0\u8f7d\uff0c\u6a21\u5f0f={}", config.mode());
            ModelStatus status = ModelStatus.ready("cloud livetranslate mode");
            runtimeConfigService.setModelStatus(status);
            return status;
        }
        runtimeConfigService.setModelStatus(ModelStatus.loading("checking local model cache"));
        log.info("\u5f00\u59cb\u786e\u4fdd\u672c\u5730 ASR \u6a21\u578b\u5c31\u7eea\uff0c\u5f15\u64ce={}\uff0c\u6a21\u578b={}\uff0c\u8bbe\u5907={}\uff0c\u8ba1\u7b97\u7c7b\u578b={}",
                config.asrEngine(), config.asrModelId(), config.asrDevice(), config.asrComputeType());
        try {
            Files.createDirectories(modelsRoot());
            serviceProcess.ensureRunning();
            ensureAsrModel(config);
            ModelStatus status = loadAsrModel(config);
            runtimeConfigService.setModelStatus(status);
            log.info("\u672c\u5730 ASR \u6a21\u578b\u5df2\u5c31\u7eea\uff1a{}", status.message());
            return status;
        } catch (RuntimeException | IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ModelStatus status = ModelStatus.error(ex.getMessage());
            runtimeConfigService.setModelStatus(status);
            log.error("\u672c\u5730 ASR \u6a21\u578b\u51c6\u5907\u5931\u8d25\uff1a{}", ex.getMessage(), ex);
            return status;
        }
    }

    public Path asrModelPath(RuntimeConfig config) {
        return modelsRoot()
                .resolve("asr")
                .resolve(config.asrEngine())
                .resolve(config.asrModelId().replace('/', '_').replace('\\', '_'));
    }

    private void ensureAsrModel(RuntimeConfig config) throws IOException, InterruptedException {
        Path path = asrModelPath(config);
        log.info("\u68c0\u67e5 ASR \u6a21\u578b\u7f13\u5b58\uff0c\u8def\u5f84={}", path.toAbsolutePath());
        if (cacheReady(path, config.asrEngine())) {
            log.info("ASR \u6a21\u578b\u7f13\u5b58\u547d\u4e2d\uff0c\u8def\u5f84={}", path.toAbsolutePath());
            return;
        }
        runtimeConfigService.setModelStatus(ModelStatus.loading("downloading " + config.asrModelId()));
        log.info("\u5f00\u59cb\u4e0b\u8f7d ASR \u6a21\u578b\uff0c\u5f15\u64ce={}\uff0c\u6a21\u578b={}\uff0c\u4e0b\u8f7d\u6e90={}\uff0c\u76ee\u6807\u8def\u5f84={}",
                config.asrEngine(), config.asrModelId(), properties.localAsr().downloadSource(), path.toAbsolutePath());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("engine", config.asrEngine());
        body.put("model_id", config.asrModelId());
        body.put("models_dir", modelsRoot().toAbsolutePath().toString());
        body.put("download_source", properties.localAsr().downloadSource());
        String requestBody = body.toString();
        log.info("\u53d1\u9001 ASR \u6a21\u578b\u4e0b\u8f7d\u8bf7\u6c42\uff0c\u8bf7\u6c42\u4f53={}", requestBody);
        HttpRequest request = HttpRequest.newBuilder(serviceUri("/models/ensure"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(properties.localAsr().modelTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.getBytes(StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.error("ASR \u6a21\u578b\u4e0b\u8f7d\u5931\u8d25\uff0cHTTP \u72b6\u6001={}\uff0c\u54cd\u5e94={}", response.statusCode(), response.body());
            throw new IllegalStateException("ASR model download failed: " + response.body());
        }
        log.info("ASR \u6a21\u578b\u4e0b\u8f7d\u5b8c\u6210\uff0c\u6a21\u578b={}", config.asrModelId());
    }

    private ModelStatus loadAsrModel(RuntimeConfig config) throws IOException, InterruptedException {
        runtimeConfigService.setModelStatus(ModelStatus.loading("loading " + config.asrModelId()));
        log.info("\u5f00\u59cb\u52a0\u8f7d ASR \u6a21\u578b\u5230\u672c\u5730\u670d\u52a1\uff0c\u5f15\u64ce={}\uff0c\u6a21\u578b={}\uff0c\u8def\u5f84={}",
                config.asrEngine(), config.asrModelId(), asrModelPath(config).toAbsolutePath());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("engine", config.asrEngine());
        body.put("model_id", config.asrModelId());
        body.put("model_path", asrModelPath(config).toAbsolutePath().toString());
        body.put("device", config.asrDevice());
        body.put("compute_type", config.asrComputeType());
        String requestBody = body.toString();
        log.info("\u53d1\u9001 ASR \u6a21\u578b\u52a0\u8f7d\u8bf7\u6c42\uff0c\u8bf7\u6c42\u4f53={}", requestBody);
        HttpRequest request = HttpRequest.newBuilder(serviceUri("/models/load"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(properties.localAsr().modelTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.getBytes(StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.error("ASR \u6a21\u578b\u52a0\u8f7d\u5931\u8d25\uff0cHTTP \u72b6\u6001={}\uff0c\u54cd\u5e94={}", response.statusCode(), response.body());
            throw new IllegalStateException("ASR model load failed: " + response.body());
        }
        log.info("ASR \u6a21\u578b\u52a0\u8f7d\u5b8c\u6210\uff0c\u5f15\u64ce={}\uff0c\u6a21\u578b={}", config.asrEngine(), config.asrModelId());
        return ModelStatus.ready(config.asrEngine() + " " + config.asrModelId());
    }

    private URI serviceUri(String path) {
        return URI.create("http://127.0.0.1:" + properties.localAsr().servicePort() + path);
    }

    private Path modelsRoot() {
        return Path.of(properties.localAsr().modelsDir());
    }

    private boolean cacheReady(Path path, String engine) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        cleanupIncompleteCache(path);
        try (var stream = Files.walk(path)) {
            boolean hasTemp = stream.anyMatch(item -> item.getFileName().toString().startsWith(TEMP_CACHE_PREFIX));
            if (hasTemp) {
                return false;
            }
        }
        if ("faster-whisper".equals(engine)) {
            return Files.isRegularFile(path.resolve("model.bin"));
        }
        if ("sensevoice".equals(engine)) {
            for (String requiredFile : FUNASR_REQUIRED_FILES) {
                if (!Files.isRegularFile(path.resolve(requiredFile))) {
                    return false;
                }
            }
            return true;
        }
        try (var stream = Files.walk(path)) {
            return stream.anyMatch(item -> Files.isRegularFile(item) && !item.getFileName().toString().startsWith("."));
        }
    }

    private void cleanupIncompleteCache(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            var tempDirs = stream
                    .filter(item -> item.getFileName().toString().startsWith(TEMP_CACHE_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path tempDir : tempDirs) {
                deleteRecursively(tempDir);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            var entries = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
