/*
 * Copyright 2010-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef KONAN_WINDOWS

#include <cstdlib>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>
#include <stdio.h>
#include <errno.h>
#include <string>
#include "Porting.h"

namespace {

extern "C" void __gcov_dump(void) __attribute__((weak));

void InitGcovEnvironmentIfNeeded() {
    if (__gcov_dump == nullptr) {
        return;
    }
    
    const char* existing_prefix = getenv("GCOV_PREFIX");
    if (existing_prefix != nullptr && strlen(existing_prefix) > 0) {
        return;
    }
    
    const char* gcov_dir = nullptr;
    
    mkdir("/data/storage/el2/base/files", 0755);
    if (mkdir("/data/storage/el2/base/files/gcov", 0755) == 0 || errno == EEXIST) {
        // write to sandboxed directory
        gcov_dir = "/data/storage/el2/base/files/gcov";
    } else {
        // the default behavior is to write to cwd
        gcov_dir = "./";
    }
    
    if (gcov_dir != nullptr) {
        setenv("GCOV_PREFIX", gcov_dir, 1);
        setenv("GCOV_PREFIX_STRIP", "99", 1);
        std::string log_msg = std::string("[GCOV] Set GCOV_PREFIX to: ") + (gcov_dir ? gcov_dir : "(null)") + "\n";
        konan::consoleWriteUtf8(log_msg.c_str(), static_cast<uint32_t>(log_msg.size()));
    }
}

} // anonymous namespace

// Run VERY early, before GCOV's own constructors
// Priority 101 ensures this runs before __llvm_gcov_init (typically priority ~default)
__attribute__((constructor(101)))
static void KotlinNativeGcovInit() {
    InitGcovEnvironmentIfNeeded();
}

#endif