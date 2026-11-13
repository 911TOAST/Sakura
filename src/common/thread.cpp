// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2013 Dolphin Emulator Project
// SPDX-FileCopyrightText: 2014 Citra Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <limits>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "common/error.h"
#include "common/logging.h"
#include "common/assert.h"
#include "common/thread.h"
#ifdef __APPLE__
#include <mach/mach.h>
#elif defined(__HAIKU__)
#include <kernel/OS.h>
#elif defined(_WIN32)
#include <windows.h>
#include "common/string_util.h"
#include "common/windows/timer_resolution.h"
#else
#if defined(__FreeBSD__)
#include <sys/cpuset.h>
#include <sys/_cpuset.h>
#include <pthread_np.h>
// Compatibility with CPUset
#define cpu_set_t cpuset_t
#elif defined(__DragonFly__) || defined(__OpenBSD__) || defined(__Bitrig__)
#include <pthread_np.h>
#endif
#include <cerrno>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#endif

#ifndef _WIN32
#include <unistd.h>
#endif

#include "common/cpu_features.h"
#ifdef ARCHITECTURE_x86_64
#ifdef _MSC_VER
#include <intrin.h>
#else
#include <x86intrin.h>
#endif
#include "common/x64/rdtsc.h"
#endif
#include "core/core_timing.h"

namespace Common {

void SetCurrentThreadPriority(ThreadPriority new_priority) {
#ifdef _WIN32
    int windows_priority = [&]() {
        switch (new_priority) {
        case ThreadPriority::Low: return THREAD_PRIORITY_BELOW_NORMAL;
        case ThreadPriority::Normal: return THREAD_PRIORITY_NORMAL;
        case ThreadPriority::High: return THREAD_PRIORITY_ABOVE_NORMAL;
        case ThreadPriority::VeryHigh: return THREAD_PRIORITY_HIGHEST;
        case ThreadPriority::Critical: return THREAD_PRIORITY_TIME_CRITICAL;
        default: return THREAD_PRIORITY_NORMAL;
        }
    }();
    SetThreadPriority(GetCurrentThread(), windows_priority);
#elif defined(__HAIKU__)
    // TODO: We have priorities for 3D rendering applications - may help lavapipe?
    int priority = [&]() {
        switch (new_priority) {
        case ThreadPriority::Low: return B_LOW_PRIORITY;
        case ThreadPriority::Normal: return B_NORMAL_PRIORITY;
        case ThreadPriority::High: return B_DISPLAY_PRIORITY;
        case ThreadPriority::VeryHigh: return B_URGENT_DISPLAY_PRIORITY;
        case ThreadPriority::Critical: return B_URGENT_PRIORITY;
        default: return B_NORMAL_PRIORITY;
        }
    }();
    set_thread_priority(find_thread(NULL), priority);
#elif defined(__linux__)
    // SCHED_OTHER only accepts a static priority of 0, so sched_get_priority_{max,min} both
    // return 0 and the pthread_setschedparam call below changed nothing. Every priority request
    // in the emulator - the Critical GPU thread, the core threads, High audio, the Low clock
    // worker - was a no-op on Android. nice is what EAS actually consults, so use that, backing
    // off in steps if RLIMIT_NICE forbids a negative value for an unprivileged app.
    const int requested_nice = [&]() {
        switch (new_priority) {
        case ThreadPriority::Low: return 10;
        case ThreadPriority::Normal: return 0;
        case ThreadPriority::High: return -8;
        case ThreadPriority::VeryHigh: return -14;
        case ThreadPriority::Critical: return -20;
        default: return 0;
        }
    }();

    const auto tid = static_cast<id_t>(syscall(SYS_gettid));
    auto try_set = [tid](int value) {
        errno = 0;
        return setpriority(PRIO_PROCESS, tid, value) == 0;
    };

    if (requested_nice >= 0) {
        try_set(requested_nice);
    } else {
        for (int candidate = requested_nice; candidate <= 0; candidate += 2) {
            if (try_set(candidate)) {
                if (candidate != requested_nice) {
                    LOG_WARNING(Common, "nice {} not permitted for this process, using {}",
                                requested_nice, candidate);
                }
                break;
            }
        }
    }
#else
    pthread_t this_thread = pthread_self();
    const auto scheduling_type = SCHED_OTHER;
    s32 max_prio = sched_get_priority_max(scheduling_type);
    s32 min_prio = sched_get_priority_min(scheduling_type);
    u32 level = (std::max)(u32(new_priority) + 1, 4U);

    struct sched_param params;
    if (max_prio > min_prio) {
        params.sched_priority = min_prio + ((max_prio - min_prio) * level) / 4;
    } else {
        params.sched_priority = min_prio - ((min_prio - max_prio) * level) / 4;
    }

    pthread_setschedparam(this_thread, scheduling_type, &params);
#endif
}

#if defined(__linux__)
namespace {
/// Capacity of one CPU in the units schedutil publishes (cpu_capacity), falling back to the
/// maximum frequency when that file is unavailable. 0 means "unknown".
std::size_t ReadCoreCapacity(std::size_t cpu) {
    const std::string base =
        "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/";
    for (const char* file : {"cpu_capacity", "cpuinfo_max_freq"}) {
        std::FILE* handle = std::fopen((base + file).c_str(), "r");
        if (handle == nullptr) {
            continue;
        }
        unsigned long value = 0;
        const int scanned = std::fscanf(handle, "%lu", &value);
        std::fclose(handle);
        if (scanned == 1 && value != 0) {
            return static_cast<std::size_t>(value);
        }
    }
    return 0;
}
} // Anonymous namespace

bool PinCurrentThreadToFastCores(u32 max_cores) {
    const long cpu_count = sysconf(_SC_NPROCESSORS_CONF);
    if (cpu_count <= 0) {
        return false;
    }
    const long last_cpu = (std::min)(cpu_count, static_cast<long>(CPU_SETSIZE));

    std::vector<std::pair<std::size_t, long>> cores;
    cores.reserve(static_cast<std::size_t>(last_cpu));
    std::size_t best_capacity = 0;
    for (long cpu = 0; cpu < last_cpu; ++cpu) {
        const std::size_t capacity = ReadCoreCapacity(static_cast<std::size_t>(cpu));
        best_capacity = (std::max)(best_capacity, capacity);
        cores.emplace_back(capacity, cpu);
    }
    if (best_capacity == 0) {
        // No cpufreq/schedutil information: leave the scheduler alone rather than guess.
        return false;
    }

    std::sort(cores.begin(), cores.end(), [](const auto& lhs, const auto& rhs) {
        return lhs.first != rhs.first ? lhs.first > rhs.first : lhs.second < rhs.second;
    });

    // A single prime core is a real topology (1+3+4, 1+4+3). Masking a thread to that one core
    // serialises it against every other pinned thread, so the fastest cluster is widened to the
    // next capacity tier until the mask holds more than one core.
    const std::size_t limit = max_cores == 0 ? 4 : max_cores;
    std::size_t fast = 1;
    while (fast < cores.size() && fast < limit && cores[fast].first == cores[0].first) {
        ++fast;
    }
    if (fast == 1 && cores.size() > 1) {
        while (fast < cores.size() && fast < limit && cores[fast].first == cores[1].first) {
            ++fast;
        }
    }

    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (std::size_t i = 0; i < fast; ++i) {
        CPU_SET(static_cast<int>(cores[i].second), &mask);
    }
    if (sched_setaffinity(0, sizeof(mask), &mask) != 0) {
        LOG_WARNING(Common, "Thread affinity refused by the kernel (errno={})", errno);
        return false;
    }
    return true;
}
#else
bool PinCurrentThreadToFastCores(u32) {
    return false;
}
#endif

void SetCurrentThreadName(const char* name) {
#ifdef _MSC_VER
    // Sets the debugger-visible name of the current thread.
    if (auto pf = (decltype(&SetThreadDescription))(void*)GetProcAddress(GetModuleHandle(TEXT("KernelBase.dll")), "SetThreadDescription"); pf)
        pf(GetCurrentThread(), UTF8ToUTF16W(name).data()); // Windows 10+
    else
        ; // No-op
#elif  defined(__APPLE__)
    pthread_setname_np(name);
#elif defined(__HAIKU__)
    rename_thread(find_thread(NULL), name);
#elif defined(__Bitrig__) || defined(__DragonFly__) || defined(__FreeBSD__) || defined(__OpenBSD__)
    pthread_set_name_np(pthread_self(), name);
#elif defined(__NetBSD__)
    pthread_setname_np(pthread_self(), "%s", (void*)name);
#elif defined(__linux__) || defined(__CYGWIN__) || defined(__sun__) || defined(__glibc__) || defined(__managarm__)
    int ret = pthread_setname_np(pthread_self(), name);
    if (ret == ERANGE) {
        // Linux limits thread names to 15 characters and will outright reject any
        // attempt to set a longer name with ERANGE.
        char buf[16];
        size_t const len = std::min<size_t>(std::strlen(name), sizeof(buf) - 1);
        std::memcpy(buf, name, len);
        buf[len] = '\0';
        pthread_setname_np(pthread_self(), buf);
    }
#elif defined(_WIN32)
    // MinGW with the POSIX threading model does not support pthread_setname_np
    // See for reference
    // https://gitlab.freedesktop.org/mesa/mesa/-/blame/main/src/util/u_thread.c?ref_type=heads#L75
    (void)name;
#else
    pthread_setname_np(pthread_self(), name);
#endif
}

// No CPU affinity pinning. Android's cpuset controller, not this process, owns the top of the
// mask: on a motorola edge 60 fusion the same process was observed on 0-6 while menuing, 0-7 in
// game and 0-2 in background, and each migration resets every thread's affinity. Anything set at
// thread start is therefore transient, and whatever sticks is inherited by threads spawned later,
// cramming present and shader-compile workers onto the big cores the emulated CPUs need. EAS
// already ranks cores by cpu_capacity, which is the same signal a pin would use.


#ifdef ARCHITECTURE_x86_64
// On Linux and UNIX systems, a futex would nominally be used to cover the costs
// the idea is that it's intuitivelly cheaper to use a direct instruction as opposed to a full futex call
// the underlying libc++ implementation uses pthread_cond_timedwait which MAY invoke a futex
// Let's pretend the OS is too expensive to jump into, and avoid ANY context switches
// this should *IN THEORY* lower CPU usage while just waiting for stuff effectively
// For windows the minimal quanta resolution is about 500us, and normal CRT cond var is 1.5ms(?)
// so may as well avoid that too
// Let's just give ALL platforms the same mechanisms (almost) for when they have umonitor OR waitpkg
#ifdef __clang__
__attribute__((target("waitpkg,mwaitx")))
#elif defined(__GNUC__)
#pragma GCC target("waitpkg")
#pragma GCC target("mwaitx")
#endif
bool Event::WaitFor(const std::chrono::nanoseconds time) {
#ifdef _WIN32
    auto const start = Common::X64::FencedRDTSC();
    auto const& caps = Common::g_cpu_caps;
    [[maybe_unused]] auto const end = start + Common::g_wall_clock.NsToTicks(time);
    if (caps.monitorx) {
        while (true) {
            // Armed monitor, as per manual, MWAITX must be conditional if the condition isn't satisfied
            // to prevent a race condition.
            _mm_monitorx(reinterpret_cast<u64*>(std::addressof(is_set)), 0, 0);
            if (!is_set.load()) {
                // RDTSC may be fenced here due to atomic load
#ifdef _MSC_VER
                auto const now = __rdtsc();
#else
                auto const now = _rdtsc();
#endif
                if (end > now) {
                    u32 const cycles = std::min<u32>((std::numeric_limits<u32>::max)(), s64(end) - s64(now));
                    // See here: https://github.com/torvalds/linux/blob/948a64995aca6820abefd17f1a4258f5835c5ad9/arch/x86/lib/delay.c#L93
                    // MWAITX accepts a 32-bit input timer which determines the total number of cycles to wait for
                    // NOT THE TOTAL ABSOLUTE TSC VALUE, it's just a delta
                    // BIT[1] = use a timer
                    // Hint = 0: Use C1 state when sleepy (means slower wakeup but better savings)
                    _mm_mwaitx(1 << 1, 0u, cycles);
                    if (!is_set.load())
                        return false;
                } else
                    return false; //timeout
            }
            bool expected = true;
            if (is_set.compare_exchange_weak(expected, false, std::memory_order_release))
                return true;
        }
    } else if (caps.waitpkg) {
        // #UD If CPUID.7.0:ECX.WAITPKG[bit 5]=0.
        while (true) {
            _umonitor(std::addressof(is_set));
            if (!is_set.load() && !_umwait(0, end)) //umwait is absolute time!!!
                return false;
            bool expected = true;
            if (is_set.compare_exchange_weak(expected, false, std::memory_order_release))
                return true;
        }
    } else {
#ifdef _MSC_VER
        while (!is_set.load() && end > __rdtsc())
            Common::Windows::SleepForOneTick();
#else
        while (!is_set.load() && end > _rdtsc())
            Common::Windows::SleepForOneTick();
#endif
        if (is_set.load())
            Reset();
        return true;
    }
#else
    std::unique_lock lk{mutex};
    if (!condvar.wait_for(lk, time, [this] { return is_set.load(); }))
        return false;
    is_set = false;
    return true;
#endif
}
#else
bool Event::WaitFor(const std::chrono::nanoseconds time) {
#ifdef _WIN32
    auto const end = Common::g_wall_clock.GetTimeNS() + time;
    while (!is_set.load() && end > Common::g_wall_clock.GetTimeNS())
        Common::Windows::SleepForOneTick();
    if (is_set.load())
        Reset();
    return true;
#else
    std::unique_lock lk{mutex};
    if (!condvar.wait_for(lk, time, [this] { return is_set.load(); }))
        return false;
    is_set = false;
    return true;
#endif
}
#endif

} // namespace Common
