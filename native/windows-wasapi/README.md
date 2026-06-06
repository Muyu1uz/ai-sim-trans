# Windows WASAPI Capture DLL

Build on Windows with Visual Studio Build Tools and CMake:

```powershell
cmake -S native/windows-wasapi -B native/windows-wasapi/build
cmake --build native/windows-wasapi/build --config Release
```

Copy `LiveTranslateAudio.dll` from the build output next to the Java app or add that directory to `java.library.path`.

The DLL captures the default render device via WASAPI loopback and returns `PCM16 mono 16kHz` chunks. The Java side configures `chunkSamples=512`, which is 32 ms at 16 kHz.
