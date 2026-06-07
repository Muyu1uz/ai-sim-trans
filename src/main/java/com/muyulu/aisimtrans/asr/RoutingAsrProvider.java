package com.muyulu.aisimtrans.asr;

import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.service.RuntimeConfigService;

@Component
@Primary
public class RoutingAsrProvider implements AsrProvider {
    private static final Logger log = LoggerFactory.getLogger(RoutingAsrProvider.class);

    private final RuntimeConfigService runtimeConfigService;
    private final AsrProvider localAsrProvider;
    private final AsrProvider dashScopeAsrProvider;
    private volatile AsrProvider activeProvider;

    public RoutingAsrProvider(
            RuntimeConfigService runtimeConfigService,
            @Qualifier("localAsrProvider") AsrProvider localAsrProvider,
            @Qualifier("dashScopeAsrProvider") AsrProvider dashScopeAsrProvider
    ) {
        this.runtimeConfigService = runtimeConfigService;
        this.localAsrProvider = localAsrProvider;
        this.dashScopeAsrProvider = dashScopeAsrProvider;
    }

    @Override
    public synchronized void start(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer) {
        activeProvider = selectProvider();
        log.info("选择 ASR Provider，模式={}，实现={}", runtimeConfigService.current().mode(), activeProvider.getClass().getSimpleName());
        activeProvider.start(audioQueue, eventConsumer);
        log.info("ASR Provider 启动完成，模式={}", runtimeConfigService.current().mode());
    }

    @Override
    public synchronized void stop() {
        if (activeProvider != null) {
            log.info("正在停止 ASR Provider，实现={}", activeProvider.getClass().getSimpleName());
            activeProvider.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return activeProvider != null && activeProvider.isRunning();
    }

    private AsrProvider selectProvider() {
        if (RuntimeConfigService.MODE_DASHSCOPE_LIVETRANSLATE.equals(runtimeConfigService.current().mode())) {
            return dashScopeAsrProvider;
        }
        return localAsrProvider;
    }
}
