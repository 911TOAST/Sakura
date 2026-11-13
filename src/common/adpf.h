#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace Common {

class Adpf {
public:
    Adpf();
    ~Adpf();
    Adpf(const Adpf&) = delete;
    Adpf& operator=(const Adpf&) = delete;

    bool Init(const int32_t* tids, size_t n, int64_t tgt);
    void SetThr(const int32_t* tids, size_t n);
    void Report(int64_t act, int64_t tgt = 0);
    void Reset();
    bool Active() const noexcept { return sess != nullptr; }

private:
    void* lib{};
    void* mgr{};
    void* sess{};
    void (*rep)(void*, int64_t){};
    void (*upd)(void*, int64_t){};
    void (*setThr)(void*, const int32_t*, size_t){};
    void (*cls)(void*){};
};

void AdpfRegisterThread(int32_t tid);
void AdpfFrameTick(int64_t act_ns, int64_t tgt_ns);
void AdpfReset();
void AdpfPurge();
uint64_t AdpfReports() noexcept;
uint64_t AdpfSessions() noexcept;

}
