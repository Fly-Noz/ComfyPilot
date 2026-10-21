cmake_minimum_required(VERSION 3.5.0)
project(ComfyPilotEntry)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

if(DEFINED PACKAGE_FIND_FILE)
    include(${PACKAGE_FIND_FILE})
endif()

include_directories(${NATIVERENDER_ROOT_PATH}/include)

# 由 harmony/build.ps1 从 ohos/ 的构建产物复制而来，不提交到仓库。
set(COMFYPILOT_LIB ${NATIVERENDER_ROOT_PATH}/../../../libs/arm64-v8a/libcomfypilot_core.so)

add_library(entry SHARED napi_init.cpp)

target_link_libraries(entry PUBLIC libace_napi.z.so ${COMFYPILOT_LIB})

# libentry.so 与 libcomfypilot_core.so 在同一个 libs/arm64-v8a 目录里。
target_link_options(entry PRIVATE "-Wl,-rpath,$ORIGIN")
