package com.muyulu.aisimtrans.correction;

import java.util.List;

public interface CorrectionReviewer {
    List<CorrectionResult> review(CorrectionRequest request);
}
