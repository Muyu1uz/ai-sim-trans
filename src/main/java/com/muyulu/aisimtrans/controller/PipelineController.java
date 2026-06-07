package com.muyulu.aisimtrans.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muyulu.aisimtrans.audio.AudioCaptureProvider;
import com.muyulu.aisimtrans.pipeline.PipelineStatus;
import com.muyulu.aisimtrans.service.SimTransPipelineService;

@RestController
@RequestMapping("/api")
public class PipelineController {
    private final SimTransPipelineService pipelineService;
    private final AudioCaptureProvider audioCaptureProvider;

    public PipelineController(SimTransPipelineService pipelineService, AudioCaptureProvider audioCaptureProvider) {
        this.pipelineService = pipelineService;
        this.audioCaptureProvider = audioCaptureProvider;
    }

    @PostMapping("/pipeline/start")
    public ResponseEntity<PipelineStatus> start() {
        if (pipelineService.status().running()) {
            pipelineService.stop();
        }
        pipelineService.start();
        return ResponseEntity.ok(pipelineService.status());
    }

    @PostMapping("/pipeline/stop")
    public ResponseEntity<PipelineStatus> stop() {
        pipelineService.stop();
        return ResponseEntity.ok(pipelineService.status());
    }

    @GetMapping("/pipeline/status")
    public PipelineStatus status() {
        return pipelineService.status();
    }

    @GetMapping("/audio/devices")
    public List<String> devices() {
        return audioCaptureProvider.listOutputDevices();
    }
}
