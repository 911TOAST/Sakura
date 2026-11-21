// SPDX-FileCopyrightText: Copyright 2026 Sakura Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <string>

namespace Sakura::Android {

/// Loads statically recompiled ("AOT") module images from the game directory
/// (`aot_cache/recompiled` or `recompiled`, searched recursively) and installs
/// the dispatcher so the game runs on Core::ArmRecomp instead of the JIT where
/// images exist. Returns the number of images loaded. Without images, nothing
/// is installed and games run as before.
int LoadRecompiledImagesForGame(const std::string& game_dir);

} // namespace Sakura::Android
