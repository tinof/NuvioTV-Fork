/* Host stand-in for the NDK's <android/log.h>: the host test prints the JNI's log lines to stderr. */
#ifndef HOSTTEST_ANDROID_LOG_H
#define HOSTTEST_ANDROID_LOG_H

#include <stdarg.h>
#include <stdio.h>

enum {
  ANDROID_LOG_DEBUG = 3,
  ANDROID_LOG_INFO = 4,
  ANDROID_LOG_ERROR = 6,
};

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
  va_list args;
  va_start(args, fmt);
  fprintf(stderr, "%c/%s: ", prio >= ANDROID_LOG_ERROR ? 'E' : prio >= ANDROID_LOG_INFO ? 'I' : 'D', tag);
  int n = vfprintf(stderr, fmt, args);
  fputc('\n', stderr);
  va_end(args);
  return n;
}

#endif
