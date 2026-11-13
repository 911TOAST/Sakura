#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <span>
#include <vector>
#include "common/common_types.h"

namespace Core {
class System;
}

namespace Tegra {
namespace Engines {
class Maxwell3D;
}
struct DynamicCachedMacro;

std::unique_ptr<DynamicCachedMacro> CompileMacroArm64(Core::System& sys, std::span<const u32> code);

uint64_t MmeJitHits() noexcept;
uint64_t MmeJitMisses() noexcept;
void MmeJitAddHit() noexcept;
void MmeJitAddMiss() noexcept;

}
