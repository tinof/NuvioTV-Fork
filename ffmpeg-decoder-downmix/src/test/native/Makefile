# Host build of the transcode test driver (not part of the Gradle build). See README.md.
#
#   make FFMPEG_HOST_DIR=<host n7.0.2 tree with libavcodec.a, libavutil.a, libswresample.a>
#
# The tree is configured with
#   --disable-everything --disable-programs --disable-doc
#   --enable-encoder=dca --enable-decoder=dca,aac --enable-swresample
# and built with `make libavcodec/libavcodec.a libavutil/libavutil.a libswresample/libswresample.a`.

FFMPEG_HOST_DIR ?= $(error set FFMPEG_HOST_DIR to the host FFmpeg n7.0.2 build tree)
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17)
JNI_DIR := ../../main/jni

CC  := clang
CXX := clang++
SAN := -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer
# The JNI's own C flags (CMakeLists.txt) for the C sources it links.
JNI_C_FLAGS := -std=c17 -Wall -Wextra -Wpedantic -Werror
CXXFLAGS := -std=c++11 -O1 -g $(SAN) -Wall -Wno-unused-function \
            -Istub -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/darwin -idirafter $(FFMPEG_HOST_DIR)
CFLAGS := $(JNI_C_FLAGS) -O1 -g $(SAN)
LIBS := $(FFMPEG_HOST_DIR)/libswresample/libswresample.a $(FFMPEG_HOST_DIR)/libavcodec/libavcodec.a \
        $(FFMPEG_HOST_DIR)/libavutil/libavutil.a
FRAMEWORKS := -framework CoreFoundation -framework CoreVideo -framework CoreMedia \
              -framework VideoToolbox -framework CoreServices -liconv -lm -lpthread

BUILD := build
C_SOURCES := $(wildcard $(JNI_DIR)/dcaxll/*.c)
C_OBJECTS := $(patsubst $(JNI_DIR)/dcaxll/%.c,$(BUILD)/%.o,$(C_SOURCES))

all: $(BUILD)/host_jni_test

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/%.o: $(JNI_DIR)/dcaxll/%.c $(wildcard $(JNI_DIR)/dcaxll/*.h) | $(BUILD)
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD)/host_jni_test: host_jni_test.cc $(JNI_DIR)/ffmpeg_jni.cc $(C_OBJECTS) | $(BUILD)
	$(CXX) $(CXXFLAGS) host_jni_test.cc $(C_OBJECTS) $(LIBS) $(FRAMEWORKS) -o $@

clean:
	rm -rf $(BUILD)

.PHONY: all clean
