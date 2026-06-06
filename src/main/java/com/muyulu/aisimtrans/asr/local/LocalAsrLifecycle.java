package com.muyulu.aisimtrans.asr.local;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.runtime.RuntimeConfigService;

@Component
public class LocalAsrLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalAsrLifecycle.class);

    private final RuntimeConfigService runtimeConfigService;
    private final LocalAsrServiceProcess serviceProcess;

    public LocalAsrLifecycle(RuntimeConfigService runtimeConfigService, LocalAsrServiceProcess serviceProcess) {
        this.runtimeConfigService = runtimeConfigService;
        this.serviceProcess = serviceProcess;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWithApplication() {
        if (!RuntimeConfigService.MODE_LOCAL_ASR.equals(runtimeConfigService.current().mode())) {
            return;
        }
        try {
            serviceProcess.ensureRunning();
        } catch (Exception ex) {
            runtimeConfigService.setModelStatus(ModelStatus.error(ex.getMessage()));
            log.error("本地 ASR 服务随应用启动失败：{}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void stopWithApplication() {
        serviceProcess.stop();
    }
}
