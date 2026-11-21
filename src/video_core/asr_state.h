#pragma once

#include <atomic>

#include "common/common_types.h"

namespace VideoCore {

struct AsrSt {
    std::atomic<u32> mode{};
    std::atomic<u32> up_us{};
    std::atomic<u32> mv_us{};
    std::atomic<u32> asr_us{};
    std::atomic<u32> jon{};
    std::atomic<u32> jg{};
    std::atomic<f32> jx{};
    std::atomic<f32> jy{};
    std::atomic<u32> rw{};
    std::atomic<u32> rh{};
};

inline AsrSt asr_st{};

}
