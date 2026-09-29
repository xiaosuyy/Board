# Board —— librime JNI 模块
#
# 把预编译的 librime 静态库（连同它的依赖）链进一个 board_rime.so。
# 静态库由 tools/fetch_rime.ps1 下载到 third_party/rime/<abi>/。
#
# 说明：
# - ANDROID_STL 使用 c++_static，因此 APK 里不需要额外的 libc++_shared.so；
# - librime 与各依赖互相引用，用 --start-group/--end-group 解决循环依赖；
# - --exclude-libs,ALL 只导出我们自己的 JNI 符号，避免和宿主 App 冲突。

cmake_minimum_required(VERSION 3.18)
project(board_rime CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT DEFINED RIME_PREBUILT_ROOT OR RIME_PREBUILT_ROOT STREQUAL "")
    set(RIME_PREBUILT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/rime")
endif ()

set(RIME_PREBUILT_DIR "${RIME_PREBUILT_ROOT}/${ANDROID_ABI}")
set(RIME_INCLUDE_DIR "${RIME_PREBUILT_DIR}/include")
set(RIME_LIB_DIR "${RIME_PREBUILT_DIR}/lib")

if (NOT EXISTS "${RIME_LIB_DIR}/librime.a")
    message(FATAL_ERROR
            "找不到 ${RIME_LIB_DIR}/librime.a\n"
            "请先在项目根目录执行：\n"
            "  powershell -ExecutionPolicy Bypass -File tools/fetch_rime.ps1")
endif ()

message(STATUS "Board: librime ABI=${ANDROID_ABI} libs=${RIME_LIB_DIR}")

add_library(board_rime SHARED board_rime.cpp)

target_include_directories(board_rime PRIVATE "${RIME_INCLUDE_DIR}")

target_link_libraries(board_rime
        -Wl,--start-group
        "${RIME_LIB_DIR}/librime.a"
        "${RIME_LIB_DIR}/libopencc.a"
        "${RIME_LIB_DIR}/libleveldb.a"
        "${RIME_LIB_DIR}/libmarisa.a"
        "${RIME_LIB_DIR}/libyaml-cpp.a"
        "${RIME_LIB_DIR}/libglog.a"
        -Wl,--end-group
        # librime/glog 在 Android 上会用到 __android_log_write
        log
)

# armeabi-v7a 上 64 位原子操作需要 libatomic
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    target_link_libraries(board_rime atomic)
endif ()

target_link_options(board_rime PRIVATE
        "-Wl,--exclude-libs,ALL"
        "-Wl,--gc-sections"
        # Android 15 起的 16KB 页大小要求
        "-Wl,-z,max-page-size=16384"
)
