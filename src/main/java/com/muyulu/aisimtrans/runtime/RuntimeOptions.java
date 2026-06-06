package com.muyulu.aisimtrans.runtime;

import java.util.List;
import java.util.Map;

public record RuntimeOptions(
        RuntimeConfig config,
        String modelStatus,
        String modelMessage,
        List<String> modes,
        List<String> vadProviders,
        List<String> asrEngines,
        Map<String, String> defaultModels
) {
}
