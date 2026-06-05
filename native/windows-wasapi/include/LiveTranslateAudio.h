#pragma once

#include <cstdint>

extern "C" {

typedef void (__stdcall *AudioCallback)(
    const int16_t* samples,
    int sampleCount,
    void* userData
);

__declspec(dllexport) int __cdecl lt_start_capture(
    const wchar_t* deviceName,
    int targetSampleRate,
    int chunkSamples,
    AudioCallback callback,
    void* userData
);

__declspec(dllexport) void __cdecl lt_stop_capture();

__declspec(dllexport) int __cdecl lt_list_output_devices(
    wchar_t* buffer,
    int bufferChars
);

__declspec(dllexport) const wchar_t* __cdecl lt_get_last_error();

}
