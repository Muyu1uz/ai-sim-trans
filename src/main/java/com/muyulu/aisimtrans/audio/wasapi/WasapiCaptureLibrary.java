package com.muyulu.aisimtrans.audio.wasapi;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

public interface WasapiCaptureLibrary extends Library {
    interface AudioCallback extends StdCallLibrary.StdCallCallback {
        void invoke(Pointer samples, int sampleCount, Pointer userData);
    }

    int lt_start_capture(
            WString deviceName,
            int targetSampleRate,
            int chunkSamples,
            AudioCallback callback,
            Pointer userData
    );

    void lt_stop_capture();

    int lt_list_output_devices(char[] buffer, int bufferChars);

    WString lt_get_last_error();
}
