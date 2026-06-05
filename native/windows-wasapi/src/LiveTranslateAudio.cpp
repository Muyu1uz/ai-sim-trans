#include "../include/LiveTranslateAudio.h"

#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <avrt.h>
#include <wrl/client.h>

#include <cmath>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {

SRWLOCK g_lock = SRWLOCK_INIT;
std::thread g_thread;
bool g_running = false;
std::wstring g_lastError;

class ExclusiveLock {
public:
    ExclusiveLock() {
        AcquireSRWLockExclusive(&g_lock);
    }

    ~ExclusiveLock() {
        if (locked_) {
            ReleaseSRWLockExclusive(&g_lock);
        }
    }

    void lock() {
        if (!locked_) {
            AcquireSRWLockExclusive(&g_lock);
            locked_ = true;
        }
    }

    void unlock() {
        if (locked_) {
            ReleaseSRWLockExclusive(&g_lock);
            locked_ = false;
        }
    }

private:
    bool locked_ = true;
};

class RunningFlagGuard {
public:
    ~RunningFlagGuard() {
        ExclusiveLock lock;
        g_running = false;
    }
};

void set_error(const std::wstring& message) {
    ExclusiveLock lock;
    g_lastError = message;
}

std::wstring hresult_message(HRESULT hr, const wchar_t* prefix) {
    wchar_t* text = nullptr;
    FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,
        hr,
        MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        reinterpret_cast<LPWSTR>(&text),
        0,
        nullptr
    );
    std::wstring result(prefix);
    result += L" HRESULT=0x";
    wchar_t hex[16];
    swprintf_s(hex, L"%08X", static_cast<unsigned int>(hr));
    result += hex;
    if (text != nullptr) {
        result += L" ";
        result += text;
        LocalFree(text);
    }
    return result;
}

float read_sample(const BYTE* frame, WORD channels, WORD bitsPerSample, WORD formatTag, int channel) {
    const BYTE* sample = frame + channel * (bitsPerSample / 8);
    if (formatTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32) {
        float value;
        std::memcpy(&value, sample, sizeof(float));
        return value;
    }
    if (bitsPerSample == 16) {
        int16_t value;
        std::memcpy(&value, sample, sizeof(int16_t));
        return static_cast<float>(value) / 32768.0f;
    }
    if (bitsPerSample == 24) {
        int32_t value = (sample[0] | (sample[1] << 8) | (sample[2] << 16));
        if (value & 0x800000) {
            value |= ~0xFFFFFF;
        }
        return static_cast<float>(value) / 8388608.0f;
    }
    if (bitsPerSample == 32) {
        int32_t value;
        std::memcpy(&value, sample, sizeof(int32_t));
        return static_cast<float>(value) / 2147483648.0f;
    }
    return 0.0f;
}

std::vector<float> mono_from_buffer(const BYTE* data, UINT32 frames, const WAVEFORMATEX* format) {
    std::vector<float> mono;
    mono.reserve(frames);
    const WORD channels = format->nChannels;
    const WORD bits = format->wBitsPerSample;
    WORD tag = format->wFormatTag;
    if (tag == WAVE_FORMAT_EXTENSIBLE) {
        const auto* extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(format);
        if (extensible->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
            tag = WAVE_FORMAT_IEEE_FLOAT;
        } else {
            tag = WAVE_FORMAT_PCM;
        }
    }

    for (UINT32 frameIndex = 0; frameIndex < frames; ++frameIndex) {
        const BYTE* frame = data + frameIndex * format->nBlockAlign;
        float sum = 0.0f;
        for (WORD channel = 0; channel < channels; ++channel) {
            sum += read_sample(frame, channels, bits, tag, channel);
        }
        mono.push_back(sum / static_cast<float>(channels));
    }
    return mono;
}

std::vector<float> resample_linear(const std::vector<float>& input, int sourceRate, int targetRate, double& position) {
    std::vector<float> output;
    if (input.empty() || sourceRate <= 0 || targetRate <= 0) {
        return output;
    }
    const double step = static_cast<double>(sourceRate) / static_cast<double>(targetRate);
    while (position + 1.0 < static_cast<double>(input.size())) {
        const int index = static_cast<int>(position);
        const double frac = position - index;
        const float sample = static_cast<float>(input[index] * (1.0 - frac) + input[index + 1] * frac);
        output.push_back(sample);
        position += step;
    }
    position -= static_cast<double>(input.size());
    if (position < 0.0) {
        position = 0.0;
    }
    return output;
}

int16_t clamp_to_pcm16(float sample) {
    const float clipped = std::max(-1.0f, std::min(1.0f, sample));
    return static_cast<int16_t>(std::lrintf(clipped * 32767.0f));
}

ComPtr<IMMDevice> default_render_device() {
    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator));
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"CoCreateInstance(MMDeviceEnumerator) failed."));
        return nullptr;
    }
    ComPtr<IMMDevice> device;
    hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"GetDefaultAudioEndpoint failed."));
        return nullptr;
    }
    return device;
}

void capture_loop(int targetSampleRate, int chunkSamples, AudioCallback callback, void* userData) {
    RunningFlagGuard runningGuard;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"CoInitializeEx failed."));
        return;
    }

    HANDLE avrtHandle = nullptr;
    DWORD taskIndex = 0;
    avrtHandle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);

    ComPtr<IMMDevice> device = default_render_device();
    if (!device) {
        CoUninitialize();
        return;
    }

    ComPtr<IAudioClient> audioClient;
    hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &audioClient);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"IMMDevice::Activate(IAudioClient) failed."));
        CoUninitialize();
        return;
    }

    WAVEFORMATEX* mixFormat = nullptr;
    hr = audioClient->GetMixFormat(&mixFormat);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"IAudioClient::GetMixFormat failed."));
        CoUninitialize();
        return;
    }

    REFERENCE_TIME bufferDuration = 10000000;
    hr = audioClient->Initialize(
        AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK,
        bufferDuration,
        0,
        mixFormat,
        nullptr
    );
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"IAudioClient::Initialize(loopback) failed."));
        CoTaskMemFree(mixFormat);
        CoUninitialize();
        return;
    }

    ComPtr<IAudioCaptureClient> captureClient;
    hr = audioClient->GetService(IID_PPV_ARGS(&captureClient));
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"IAudioClient::GetService(IAudioCaptureClient) failed."));
        CoTaskMemFree(mixFormat);
        CoUninitialize();
        return;
    }

    hr = audioClient->Start();
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"IAudioClient::Start failed."));
        CoTaskMemFree(mixFormat);
        CoUninitialize();
        return;
    }

    std::vector<int16_t> chunk;
    chunk.reserve(chunkSamples);
    double resamplePosition = 0.0;

    while (true) {
        {
            ExclusiveLock lock;
            if (!g_running) {
                break;
            }
        }

        UINT32 packetLength = 0;
        hr = captureClient->GetNextPacketSize(&packetLength);
        if (FAILED(hr)) {
            set_error(hresult_message(hr, L"GetNextPacketSize failed."));
            break;
        }

        if (packetLength == 0) {
            Sleep(5);
            continue;
        }

        BYTE* data = nullptr;
        UINT32 frames = 0;
        DWORD flags = 0;
        hr = captureClient->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
        if (FAILED(hr)) {
            set_error(hresult_message(hr, L"IAudioCaptureClient::GetBuffer failed."));
            break;
        }

        std::vector<float> mono;
        if ((flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0) {
            mono.assign(frames, 0.0f);
        } else {
            mono = mono_from_buffer(data, frames, mixFormat);
        }

        std::vector<float> resampled = resample_linear(mono, mixFormat->nSamplesPerSec, targetSampleRate, resamplePosition);
        for (float sample : resampled) {
            chunk.push_back(clamp_to_pcm16(sample));
            if (static_cast<int>(chunk.size()) == chunkSamples) {
                callback(chunk.data(), chunkSamples, userData);
                chunk.clear();
            }
        }

        captureClient->ReleaseBuffer(frames);
    }

    audioClient->Stop();
    CoTaskMemFree(mixFormat);
    if (avrtHandle != nullptr) {
        AvRevertMmThreadCharacteristics(avrtHandle);
    }
    CoUninitialize();
}

} // namespace

extern "C" __declspec(dllexport) int __cdecl lt_start_capture(
    const wchar_t*,
    int targetSampleRate,
    int chunkSamples,
    AudioCallback callback,
    void* userData
) {
    if (targetSampleRate <= 0 || chunkSamples <= 0 || callback == nullptr) {
        set_error(L"Invalid capture arguments.");
        return 1;
    }

    ExclusiveLock lock;
    if (g_running) {
        return 0;
    }
    if (g_thread.joinable()) {
        lock.unlock();
        g_thread.join();
        lock.lock();
    }
    g_lastError.clear();
    g_running = true;
    g_thread = std::thread(capture_loop, targetSampleRate, chunkSamples, callback, userData);
    return 0;
}

extern "C" __declspec(dllexport) void __cdecl lt_stop_capture() {
    {
        ExclusiveLock lock;
        g_running = false;
    }
    if (g_thread.joinable()) {
        g_thread.join();
    }
}

extern "C" __declspec(dllexport) int __cdecl lt_list_output_devices(wchar_t* buffer, int bufferChars) {
    if (buffer == nullptr || bufferChars <= 0) {
        return 0;
    }
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"CoInitializeEx failed."));
        return 0;
    }

    ComPtr<IMMDeviceEnumerator> enumerator;
    hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator));
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"CoCreateInstance(MMDeviceEnumerator) failed."));
        CoUninitialize();
        return 0;
    }

    ComPtr<IMMDeviceCollection> devices;
    hr = enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &devices);
    if (FAILED(hr)) {
        set_error(hresult_message(hr, L"EnumAudioEndpoints failed."));
        CoUninitialize();
        return 0;
    }

    UINT count = 0;
    devices->GetCount(&count);
    std::wstring output;
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> device;
        if (FAILED(devices->Item(i, &device))) {
            continue;
        }
        ComPtr<IPropertyStore> props;
        if (FAILED(device->OpenPropertyStore(STGM_READ, &props))) {
            continue;
        }
        PROPVARIANT name;
        PropVariantInit(&name);
        if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) && name.vt == VT_LPWSTR) {
            output += name.pwszVal;
            output += L"\n";
        }
        PropVariantClear(&name);
    }

    const int copyChars = std::min(bufferChars - 1, static_cast<int>(output.size()));
    wcsncpy_s(buffer, bufferChars, output.c_str(), copyChars);
    buffer[copyChars] = L'\0';
    CoUninitialize();
    return copyChars;
}

extern "C" __declspec(dllexport) const wchar_t* __cdecl lt_get_last_error() {
    ExclusiveLock lock;
    return g_lastError.c_str();
}
