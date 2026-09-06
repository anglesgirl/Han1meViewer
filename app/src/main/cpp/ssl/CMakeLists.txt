cmake_minimum_required(VERSION 3.22.0)
project(han1me_ech LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Download and build boringssl (use main branch, not master)
include(FetchContent)
FetchContent_Declare(
    boringssl
    GIT_REPOSITORY https://boringssl.googlesource.com/boringssl
    GIT_TAG main
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(boringssl)

# Download and build curl
FetchContent_Declare(
    curl
    GIT_REPOSITORY https://github.com/curl/curl.git
    GIT_TAG curl-8_8_0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(curl)

add_library(han1me_ech SHARED native-lib.cpp)
target_compile_features(han1me_ech PRIVATE cxx_std_20)
target_link_libraries(han1me_ech PRIVATE boringssl::ssl boringssl::crypto curl::libcurl)

# Android specifics
if(ANDROID)
    find_library(android_lib android)
    find_library(log_lib log)
    target_link_libraries(han1me_ech PRIVATE ${android_lib} ${log_lib})
endif()
