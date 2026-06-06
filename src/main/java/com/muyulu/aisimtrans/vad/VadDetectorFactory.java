package com.muyulu.aisimtrans.vad;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.config.SimTransProperties;

@Component
public class VadDetectorFactory {
    private final SimTransProperties properties;

    public VadDetectorFactory(SimTransProperties properties) {
        this.properties = properties;
    }

    public VadDetector create(String provider) {
        if ("disabled".equals(provider)) {
            return new DisabledVadDetector();
        }
        if ("silero".equals(provider)) {
            return new SileroVadDetector(Path.of(properties.vad().sileroModel()), properties.vad().threshold());
        }
        return new EnergyVadDetector(Math.max(0.0001, properties.vad().energyThreshold()));
    }
}
