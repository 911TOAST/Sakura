#!/bin/sh -e

# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# Formats the retained C++ modules (Android-only build).
find src/common src/core src/network src/video_core src/audio_core src/input_common src/frontend_common src/shader_recompiler src/hid_core src/nintendo_library -iname "*.h" -o -iname "*.cpp" | xargs clang-format -i -style=file:src/.clang-format
