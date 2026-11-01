// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2013 Dolphin Emulator Project
// SPDX-FileCopyrightText: 2014 Citra Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include "common/logging.h"

// Sometimes we want to try to continue even after hitting an assert.
// However touching this file yields a global recompilation as this header is included almost
// everywhere. So let's just move the handling of the failed assert to a single cpp file.

void AssertFailSoftImpl();
[[noreturn]] void AssertFatalImpl();

#ifdef NDEBUG
// Release builds: ASSERT_MSG only logs through AssertFailSoftImpl and then returns, so it is a
// diagnostic, never control flow. The condition is still evaluated and discarded: a handful of
// call sites put real work in it (`ASSERT(--m_resident_map_count >= 0)`), and dropping that would
// corrupt their bookkeeping. Side-effect-free conditions are dead-code eliminated, so the check
// costs nothing, but nothing is silently skipped either. Stub notices stay - those fire only when
// an unimplemented path is actually reached, which is cold and worth seeing.
#define ASSERT_MSG(_a_, ...) do { (void)(_a_); } while (0)
#define ASSERT(_a_) ASSERT_MSG(_a_, "{}", #_a_)
#define UNIMPLEMENTED() LOG_ERROR(Debug, __FILE__ ": unimplemented")
#define UNIMPLEMENTED_MSG(...) LOG_ERROR(Debug, __FILE__ ": unimplemented " __VA_ARGS__)
#define UNIMPLEMENTED_IF(cond)                                                                     \
    do {                                                                                           \
        if (cond) {                                                                                \
            LOG_ERROR(Debug, __FILE__ ": unimplemented");                                          \
        }                                                                                          \
    } while (0)
#define UNIMPLEMENTED_IF_MSG(cond, ...)                                                            \
    do {                                                                                           \
        if (cond) {                                                                                \
            LOG_ERROR(Debug, __FILE__ ": unimplemented " __VA_ARGS__);                             \
        }                                                                                          \
    } while (0)
#else
// Assert checks must stay cheap. The lambda used to be forced out of line, which turned every
// site into a real call even when the condition was statically true; letting the compiler inline
// it keeps the check and drops the call, and lets it fold away where it is known at compile time.
#define ASSERT_MSG(_a_, ...)                                                                       \
    ([&]() {                                                                                       \
        auto&& assert_condition = (_a_);                                                           \
        if (!(assert_condition)) [[unlikely]] {                                                   \
            LOG_CRITICAL(Debug, __FILE__ ": assert " __VA_ARGS__);                                \
            AssertFailSoftImpl();                                                                  \
        }                                                                                          \
    }())
#define ASSERT(_a_) ASSERT_MSG(_a_, "{}", #_a_)

#define UNIMPLEMENTED() ASSERT(false && "Unimplemented!")
#define UNIMPLEMENTED_MSG(...) ASSERT_MSG(false, __VA_ARGS__)
#define UNIMPLEMENTED_IF(cond) ASSERT((!(cond)) && "Unimplemented!")
#define UNIMPLEMENTED_IF_MSG(cond, ...) ASSERT_MSG(!(cond), __VA_ARGS__)
#endif

#define UNREACHABLE_MSG(...)                                                                       \
    do {                                                                                           \
        LOG_CRITICAL(Debug, __FILE__ ": unreachable " __VA_ARGS__);                               \
        AssertFatalImpl();                                                                         \
    } while (0)
#define UNREACHABLE() UNREACHABLE_MSG("")

#ifdef _DEBUG
#define DEBUG_ASSERT(_a_) ASSERT(_a_)
#define DEBUG_ASSERT_MSG(_a_, ...) ASSERT_MSG(_a_, __VA_ARGS__)
#else // not debug
#define DEBUG_ASSERT(_a_)                                                                          \
    do {                                                                                           \
    } while (0)
#define DEBUG_ASSERT_MSG(_a_, _desc_, ...)                                                         \
    do {                                                                                           \
    } while (0)
#endif

// If the assert is ignored, execute _b_
#define ASSERT_OR_EXECUTE_MSG(_a_, _b_, ...)                                                       \
    do {                                                                                           \
        ASSERT_MSG(_a_, __VA_ARGS__);                                                              \
        if (!(_a_)) { _b_ }                                                                        \
    } while (0)

// If the assert is ignored, execute _b_
#define ASSERT_OR_EXECUTE(_a_, _b_) ASSERT_OR_EXECUTE_MSG(_a_, _b_, "{}", #_a_)
