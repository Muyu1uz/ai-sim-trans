package com.muyulu.aisimtrans.audio.wasapi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.audio.AudioCaptureException;
import com.muyulu.aisimtrans.audio.AudioCaptureProvider;
import com.muyulu.aisimtrans.audio.AudioChunk;
import com.muyulu.aisimtrans.audio.AudioChunkListener;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

@Component
public class WasapiLoopbackCaptureProvider implements AudioCaptureProvider {
    private final SimTransProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private volatile WasapiCaptureLibrary library;
    private volatile WasapiCaptureLibrary.AudioCallback callback;

    public WasapiLoopbackCaptureProvider(SimTransProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start(AudioChunkListener listener) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            WasapiCaptureLibrary nativeLibrary = library();
            callback = (samples, sampleCount, userData) -> {
                short[] pcm = samples.getShortArray(0, sampleCount);
                AudioChunk chunk = AudioChunk.fromPcm16(
                        pcm,
                        properties.audio().sampleRate(),
                        properties.audio().channels(),
                        sequence.incrementAndGet(),
                        System.nanoTime()
                );
                listener.onAudioChunk(chunk);
            };

            String deviceName = properties.audio().deviceName();
            int result = nativeLibrary.lt_start_capture(
                    deviceName == null || deviceName.isBlank() ? null : new WString(deviceName),
                    properties.audio().sampleRate(),
                    properties.audio().chunkSamples(),
                    callback,
                    Pointer.NULL
            );
            if (result != 0) {
                running.set(false);
                throw new AudioCaptureException("WASAPI capture failed: " + lastError(nativeLibrary));
            }
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (library != null) {
            library.lt_stop_capture();
        }
        callback = null;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public List<String> listOutputDevices() {
        char[] buffer = new char[8192];
        int chars = library().lt_list_output_devices(buffer, buffer.length);
        if (chars <= 0) {
            return List.of();
        }
        String joined = new String(buffer, 0, Math.min(chars, buffer.length)).trim();
        if (joined.isEmpty()) {
            return List.of();
        }
        List<String> devices = new ArrayList<>();
        for (String device : joined.split("\\R")) {
            if (!device.isBlank()) {
                devices.add(device.trim());
            }
        }
        return devices;
    }

    private WasapiCaptureLibrary library() {
        WasapiCaptureLibrary local = library;
        if (local != null) {
            return local;
        }
        try {
            local = Native.load(properties.audio().nativeLibrary(), WasapiCaptureLibrary.class);
            library = local;
            return local;
        } catch (UnsatisfiedLinkError ex) {
            throw new AudioCaptureException(
                    "Cannot load native WASAPI library '" + properties.audio().nativeLibrary()
                            + "'. Build native/windows-wasapi and place the DLL on java.library.path.",
                    ex
            );
        }
    }

    private String lastError(WasapiCaptureLibrary nativeLibrary) {
        WString error = nativeLibrary.lt_get_last_error();
        return error == null ? "unknown native error" : error.toString();
    }
}
