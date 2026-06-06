package com.muyulu.aisimtrans.translation;

import java.util.function.Consumer;

public interface TranslationProvider {
    void translate(TranslationRequest request, Consumer<TranslationDelta> deltaConsumer);
}
