package com.muyulu.aisimtrans.correction;

import java.util.List;

public record CorrectionRequest(
        List<CorrectionSegment> segments,
        String targetLanguage
) {
}
