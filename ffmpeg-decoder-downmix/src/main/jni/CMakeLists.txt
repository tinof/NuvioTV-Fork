#
# Copyright 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# LINT.IfChange
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
# LINT.ThenChange(../../../build.gradle)

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

project(libffmpegJNI C CXX)

if(NOT DEFINED FFMPEG_SOURCE_DIR OR NOT DEFINED FFMPEG_BUILD_DIR)
    message(FATAL_ERROR "FFMPEG_SOURCE_DIR and FFMPEG_BUILD_DIR must be provided via Gradle properties.")
endif()

set(ffmpeg_source_dir "${FFMPEG_SOURCE_DIR}")
set(ffmpeg_build_dir "${FFMPEG_BUILD_DIR}/${ANDROID_ABI}")
set(ffmpeg_include_dir "${ffmpeg_source_dir}")

foreach(ffmpeg_lib avutil swresample avcodec)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_build_dir}/${ffmpeg_lib_filename})
    add_library(
            ${ffmpeg_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_lib_file_path})
endforeach()

find_library(android_log_lib log)
find_library(android_lib android)
find_library(z_lib z)
find_library(m_lib m)

add_library(ffmpegJNI
            SHARED
            ffmpeg_jni.cc
            dtshdma/dtshdma_enc.c)

# The DTS-HD writer is portable C17 (dtshdma/PROVENANCE.md). C_STANDARD applies to the C
# sources only, so the JNI stays C++11. The copied library must stay warning-clean: it is
# never edited here, so a warning is fixed in dts-hdma and copied again.
set_target_properties(ffmpegJNI PROPERTIES C_STANDARD 17 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
set_source_files_properties(dtshdma/dtshdma_enc.c
                            PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Wpedantic;-Werror")

# FFmpeg ships a top-level file named VERSION. On case-insensitive hosts a
# regular -I for the FFmpeg root can shadow libc++'s <version> header. Search
# the FFmpeg root only after the toolchain's system include directories.
target_compile_options(ffmpegJNI PRIVATE
                       "-idirafter"
                       "${ffmpeg_include_dir}")

target_link_libraries(ffmpegJNI
                      PRIVATE ${android_lib}
                      PRIVATE swresample
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE ${android_log_lib}
                      PRIVATE ${z_lib}
                      PRIVATE ${m_lib})

# Additional flags needed for "arm64-v8a" from NDK 23.1.7779620 and above.
# See https://github.com/google/ExoPlayer/issues/9933#issuecomment-1029775358.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()

# Enable 16 KB ELF alignment.
target_link_options(ffmpegJNI
                    PRIVATE "-Wl,-z,max-page-size=16384")
