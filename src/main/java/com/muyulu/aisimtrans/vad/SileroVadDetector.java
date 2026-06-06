package com.muyulu.aisimtrans.vad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.muyulu.aisimtrans.audio.AudioChunk;

public class SileroVadDetector implements VadDetector, AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final double threshold;
    private float[][][] state = new float[2][1][128];

    public SileroVadDetector(Path modelPath, double threshold) {
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException("Silero VAD ONNX model not found: " + modelPath.toAbsolutePath());
        }
        try {
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(modelPath.toString(), new OrtSession.SessionOptions());
            this.threshold = threshold;
        } catch (OrtException ex) {
            throw new IllegalStateException("Failed to load Silero VAD ONNX model: " + modelPath.toAbsolutePath(), ex);
        }
    }

    @Override
    public synchronized boolean isSpeech(AudioChunk chunk) {
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(toFloatMono(chunk)), new long[]{1, chunk.sampleCount()});
             OnnxTensor stateTensor = OnnxTensor.createTensor(environment, state);
             OnnxTensor sr = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[]{chunk.sampleRate()}), new long[]{1});
             OrtSession.Result result = session.run(Map.of("input", input, "state", stateTensor, "sr", sr))) {
            float probability = speechProbability(result);
            updateState(result);
            return probability >= threshold;
        } catch (OrtException ex) {
            throw new IllegalStateException("Silero VAD inference failed", ex);
        }
    }

    private float[] toFloatMono(AudioChunk chunk) {
        ByteBuffer buffer = ByteBuffer.wrap(chunk.pcm16le()).order(ByteOrder.LITTLE_ENDIAN);
        int channels = Math.max(1, chunk.channels());
        int sampleCount = chunk.sampleCount();
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount && buffer.remaining() >= Short.BYTES; i++) {
            int mixed = 0;
            for (int channel = 0; channel < channels && buffer.remaining() >= Short.BYTES; channel++) {
                mixed += buffer.getShort();
            }
            samples[i] = mixed / (float) channels / Short.MAX_VALUE;
        }
        return samples;
    }

    private float speechProbability(OrtSession.Result result) throws OrtException {
        Object value = result.get(0).getValue();
        if (value instanceof float[][] output) {
            return output[0][0];
        }
        if (value instanceof float[] output) {
            return output[0];
        }
        throw new IllegalStateException("Unexpected Silero VAD output shape: " + value.getClass().getName());
    }

    private void updateState(OrtSession.Result result) throws OrtException {
        Optional<OnnxValue> namedState = result.get("stateN").or(() -> result.get("state_n"));
        OnnxValue stateValue = namedState.orElseGet(() -> {
            if (result.size() <= 1) {
                throw new IllegalStateException("Silero VAD state output not found");
            }
            return result.get(1);
        });
        Object value = stateValue.getValue();
        if (value instanceof float[][][] nextState) {
            state = nextState;
            return;
        }
        throw new IllegalStateException("Unexpected Silero VAD state shape: " + value.getClass().getName());
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
