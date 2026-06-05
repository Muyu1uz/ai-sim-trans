cmake_minimum_required(VERSION 3.20)
project(LiveTranslateAudio LANGUAGES CXX)

add_library(LiveTranslateAudio SHARED
    src/LiveTranslateAudio.cpp
)

target_compile_features(LiveTranslateAudio PRIVATE cxx_std_20)
target_compile_definitions(LiveTranslateAudio PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(LiveTranslateAudio PRIVATE ole32 avrt)
