/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * Thin shims over <spawn.h> / <sys/wait.h> for K/N iOS targets.
 *
 * Kotlin/Native's built-in `platform.posix` binding on iOS deliberately excludes
 * `posix_spawn(2)` and the WIFEXITED/WEXITSTATUS status-word macros — the upstream
 * rationale is that iOS apps can't usefully spawn child processes, so JetBrains omits
 * them from the ios_* platform klibs (they exist in the linux_* and macos_* klibs).
 * That's correct for production iOS code but blocks the CRA-80 inter-process test
 * from calling these system calls directly.
 *
 * These shims are `static inline` so K/N cinterop compiles them per-caller without
 * needing a pre-built static library — keeps the build-time cost comparable to the
 * existing ObjCExceptionCatcher cinterop (~nothing) while giving Kotlin code a typed
 * handle on the system primitives.
 *
 * Simulator-only in practice: the call site
 * (`iosTest/ConcurrentCompressInterProcessTest`) is gated on an env var set by Gradle
 * on the `iosSimulatorArm64Test` task, and real iOS devices don't let unsigned
 * executables be launched via posix_spawn anyway. Exposing the binding on all three
 * iOS targets (simulator arm64, device arm64, simulator x64) keeps the cinterop
 * wiring symmetric and costs nothing at runtime since nothing in `iosMain` calls it.
 */

#pragma once

#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>
#include <errno.h>
#include <crt_externs.h>  /* _NSGetEnviron() — Darwin's way to reach `environ` from a
                             shared-library context where the plain `extern char **environ`
                             linker trick is unreliable (see dyld(1)). */

/**
 * Minimal posix_spawn wrapper. `argv` must be NULL-terminated; the convention on
 * Darwin is that argv[0] is the executable basename, not the full path — but the
 * `path` argument is the absolute path used for the actual exec. Pass NULL for
 * `envp` to inherit the parent's environment (required on iOS simulator so the
 * child picks up DYLD_* settings that keep it inside the simulator bootstrap).
 *
 * Returns the child pid on success, or -1 with `errno` set to the POSIX error
 * code that posix_spawn returned. The kernel puts the error in the return value,
 * not in errno, so we translate here to keep callers' error-handling uniform.
 */
static inline int kmp_posix_spawn(
    const char* path,
    char* const argv[],
    char* const envp[]
) {
    pid_t pid = 0;
    /*
     * Darwin man posix_spawn(2) documents `envp == NULL` as "unspecified behavior",
     * and empirically on iOS simulator hosts the child loses DYLD_ROOT_PATH / DYLD_
     * LIBRARY_PATH (which simctl injects to point at the simulator SDK). Without
     * those, dyld in the child prints "DYLD_ROOT_PATH not set for simulator program"
     * and aborts. Explicitly forwarding `*_NSGetEnviron()` preserves the full parent
     * env including the simulator DYLD_* triad.
     */
    char* const* effective_envp = envp ? envp : *_NSGetEnviron();
    int rc = posix_spawn(&pid, path, NULL, NULL, argv, effective_envp);
    if (rc != 0) {
        errno = rc;
        return -1;
    }
    return (int) pid;
}

/**
 * Blocking wait for a specific child. Retries on EINTR so a signal during the
 * test (Xcode attaching lldb, a CI cancellation, …) doesn't turn into a spurious
 * failure. Returns the exit code (0..255) if the child exited normally, or -1
 * if it was killed by a signal or waitpid itself failed.
 */
static inline int kmp_waitpid_exit(int pid) {
    int status = 0;
    pid_t waited;
    do {
        waited = waitpid((pid_t) pid, &status, 0);
    } while (waited == -1 && errno == EINTR);
    if (waited == -1) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return -1;
}
