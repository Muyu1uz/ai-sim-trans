package com.muyulu.aisimtrans.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muyulu.aisimtrans.asr.local.LocalAsrModelManager;
import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.service.RuntimeConfigService;
import com.muyulu.aisimtrans.runtime.RuntimeConfigUpdate;
import com.muyulu.aisimtrans.runtime.RuntimeOptions;

@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
    private final RuntimeConfigService runtimeConfigService;
    private final LocalAsrModelManager localAsrModelManager;

    public RuntimeController(RuntimeConfigService runtimeConfigService, LocalAsrModelManager localAsrModelManager) {
        this.runtimeConfigService = runtimeConfigService;
        this.localAsrModelManager = localAsrModelManager;
    }

    @GetMapping("/options")
    public RuntimeOptions options() {
        return runtimeConfigService.options();
    }

    @PostMapping("/config")
    public RuntimeConfig update(@RequestBody RuntimeConfigUpdate update) {
        return runtimeConfigService.update(update);
    }

    @PostMapping("/model/load")
    public ResponseEntity<ModelStatus> loadModel() {
        return ResponseEntity.ok(localAsrModelManager.ensureLoaded());
    }
}
