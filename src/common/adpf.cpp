#include "common/adpf.h"

#include <algorithm>
#if defined(__ANDROID__)
#include <dlfcn.h>
#endif

namespace Common {

static std::atomic<uint64_t> adpf_reports{0};
static std::atomic<uint64_t> adpf_sessions{0};

uint64_t AdpfReports() noexcept {
    return adpf_reports.load(std::memory_order_relaxed);
}

uint64_t AdpfSessions() noexcept {
    return adpf_sessions.load(std::memory_order_relaxed);
}

Adpf::Adpf() = default;

Adpf::~Adpf() {
    Reset();
#if defined(__ANDROID__)
    if (lib) {
        dlclose(lib);
        lib = nullptr;
    }
#endif
}

void Adpf::Reset() {
    if (sess && cls) {
        cls(sess);
    }
    sess = nullptr;
    rep  = nullptr;
    upd  = nullptr;
    setThr = nullptr;
    cls  = nullptr;
    mgr  = nullptr;
}

bool Adpf::Init(const int32_t* tids, size_t n, int64_t tgt) {
#if defined(__ANDROID__)
    if (sess) {
        Reset();
    }
    if (!lib) {
        lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!lib) {
        return false;
    }
    using GetMgrFn    = void* (*)();
    using CreateFn    = void* (*)(void*, const int32_t*, size_t, int64_t);
    using SetThrFn    = void  (*)(void*, const int32_t*, size_t);
    using ReportFn    = void  (*)(void*, int64_t);
    using CloseFn     = void  (*)(void*);

    auto fn_get    = reinterpret_cast<GetMgrFn>(dlsym(lib, "APerformanceHint_getManager"));
    auto fn_create = reinterpret_cast<CreateFn>(dlsym(lib, "APerformanceHint_createSession"));
    auto fn_cls    = reinterpret_cast<CloseFn> (dlsym(lib, "APerformanceHint_closeSession"));

    if (!fn_get || !fn_create || !fn_cls) {
        return false;
    }

    rep    = reinterpret_cast<ReportFn>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
    upd    = reinterpret_cast<ReportFn>(dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
    setThr = reinterpret_cast<SetThrFn>(dlsym(lib, "APerformanceHint_setThreads"));
    cls    = fn_cls;

    mgr = fn_get();
    if (!mgr) {
        return false;
    }
    sess = fn_create(mgr, tids, n, tgt > 0 ? tgt : 16666666LL);
    if (!sess) {
        return false;
    }
    adpf_sessions.fetch_add(1, std::memory_order_relaxed);
    return true;
#else
    (void)tids;
    (void)n;
    (void)tgt;
    return false;
#endif
}

void Adpf::SetThr(const int32_t* tids, size_t n) {
    if (sess && setThr) {
        setThr(sess, tids, n);
    }
}

void Adpf::Report(int64_t act, int64_t tgt) {
    if (!sess) {
        return;
    }
    if (rep) {
        rep(sess, act);
    }
    if (tgt > 0 && upd) {
        upd(sess, tgt);
    }
    adpf_reports.fetch_add(1, std::memory_order_relaxed);
}

static constexpr size_t MAX_TIDS = 8;
static int32_t  g_tids[MAX_TIDS]{};
static std::atomic<size_t> g_ntids{0};
static std::atomic_flag    g_tid_lock = ATOMIC_FLAG_INIT;
static Adpf g_adpf;

void AdpfRegisterThread(int32_t tid) {
    if (tid <= 0) {
        return;
    }
    while (g_tid_lock.test_and_set(std::memory_order_acquire)) {}
    size_t cur = g_ntids.load(std::memory_order_relaxed);
    for (size_t i = 0; i < cur; ++i) {
        if (g_tids[i] == tid) {
            g_tid_lock.clear(std::memory_order_release);
            return;
        }
    }
    if (cur < MAX_TIDS) {
        g_tids[cur] = tid;
        g_ntids.store(cur + 1, std::memory_order_relaxed);
    }
    g_tid_lock.clear(std::memory_order_release);

    if (g_adpf.Active()) {
        size_t n = g_ntids.load(std::memory_order_relaxed);
        g_adpf.SetThr(g_tids, n);
    }
}

void AdpfReset() {
    while (g_tid_lock.test_and_set(std::memory_order_acquire)) {}
    g_adpf.Reset();
    g_ntids.store(0, std::memory_order_relaxed);
    for (auto& t : g_tids) { t = 0; }
    g_tid_lock.clear(std::memory_order_release);
}

void AdpfFrameTick(int64_t act_ns, int64_t tgt_ns) {
    if (!g_adpf.Active()) {
        size_t n = g_ntids.load(std::memory_order_relaxed);
        if (n > 0) {
            g_adpf.Init(g_tids, std::min(n, MAX_TIDS), tgt_ns);
        }
        return;
    }
    g_adpf.Report(act_ns, tgt_ns);
}

}
