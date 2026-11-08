// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "firmware_manager.h"
#include <filesystem>
#include <common/fs/fs_paths.h>

#include "common/fs/fs.h"
#include "common/fs/path_util.h"

#include "common/logging.h"
#include "common/settings.h"

#include "core/crypto/key_manager.h"
#include "frontend_common/content_manager.h"

#ifdef __ANDROID__
#include <jni.h>
#include <common/android/id_cache.h>
#include <common/android/android_common.h>
#endif

FirmwareManager::KeyInstallResult
FirmwareManager::InstallKeys(std::string location, std::string extension) {
    LOG_INFO(Frontend, "Installing key files from {}", location);

    const auto keys_dir = Common::FS::GetSuyuPath(Common::FS::SuyuPath::KeysDir);

#ifdef __ANDROID__
    JNIEnv *env = Common::Android::GetEnvForThread();

    jstring jsrc = Common::Android::ToJString(env, location);

    jclass native = Common::Android::GetNativeLibraryClass();
    jmethodID getExtension = Common::Android::GetFileExtension();

    jstring jext = static_cast<jstring>(env->CallStaticObjectMethod(
        native,
        getExtension,
        jsrc
    ));

    std::string ext = Common::Android::GetJString(env, jext);

    if (ext != extension) {
        return ErrorWrongFilename;
    }

    jmethodID copyToStorage = Common::Android::GetCopyToStorage();
    jstring jdest = Common::Android::ToJString(env, keys_dir.string() + "/");

    jboolean copyResult = env->CallStaticBooleanMethod(
        native,
        copyToStorage,
        jsrc,
        jdest
    );

    if (!copyResult) {
        return ErrorFailedCopy;
    }
#else
    if (!location.ends_with(extension)) {
        return ErrorWrongFilename;
    }

    bool prod_keys_found = false;

    const std::filesystem::path prod_key_path = location;
    const std::filesystem::path key_source_path = prod_key_path.parent_path();

    if (!Common::FS::IsDir(key_source_path)) {
        return InvalidDir;
    }

    std::vector<std::filesystem::path> source_key_files;

    if (Common::FS::Exists(prod_key_path)) {
        prod_keys_found = true;
        source_key_files.emplace_back(prod_key_path);
    }

    if (Common::FS::Exists(key_source_path / "title.keys")) {
        source_key_files.emplace_back(key_source_path / "title.keys");
    }

    if (Common::FS::Exists(key_source_path / "key_retail.bin")) {
        source_key_files.emplace_back(key_source_path / "key_retail.bin");
    }

    if (source_key_files.empty() || !prod_keys_found) {
        return ErrorWrongFilename;
    }

    for (const auto &key_file : source_key_files) {
        std::filesystem::path destination_key_file = keys_dir / key_file.filename();
        if (!std::filesystem::copy_file(key_file,
                                        destination_key_file,
                                        std::filesystem::copy_options::overwrite_existing)) {
            LOG_ERROR(Frontend,
                      "Failed to copy file {} to {}",
                      key_file.string(),
                      destination_key_file.string());
            return ErrorFailedCopy;
        }
    }
#endif

    // Reinitialize the key manager
    Core::Crypto::KeyManager::Instance().ReloadKeys();

    if (ContentManager::AreKeysPresent()) {
        return Success;
    }

    // Let the frontend handle everything else
    return ErrorFailedInit;
}

FirmwareManager::FirmwareCheckResult FirmwareManager::VerifyFirmware(Core::System &system) {
    if (!CheckFirmwarePresence(system)) {
        return ErrorFirmwareMissing;
    } else {
        const auto pair = GetFirmwareVersion(system);
        const auto result = pair.second;

        if (result.IsError()) {
            LOG_INFO(Frontend, "Unable to read firmware");
            return ErrorFirmwareCorrupted;
        }
    }

    return FirmwareGood;
}

void FirmwareManager::LogStartupStatus(Core::System &system) {
    const bool has_keys = Core::Crypto::KeyManager::KeyFileExists(false);
    const bool has_firmware = CheckFirmwarePresence(system);
    const bool firmware_fallback = Settings::values.fake_system_applets.GetValue();

    LOG_INFO(Frontend, "Keys (prod.keys): {}", has_keys ? "installed" : "missing");
    LOG_INFO(Frontend, "System firmware: {}{}", has_firmware ? "installed" : "missing",
             (!has_firmware && firmware_fallback) ? " (optional, HLE applet fallbacks enabled)"
                                                  : "");

    if (!has_keys) {
        LOG_WARNING(Frontend,
                    "No prod.keys found. Encrypted games cannot be decrypted and will fail to "
                    "boot. This build ships no keys: dump them from your own console and install "
                    "them from the settings screen. Unencrypted homebrew can still run.");
    }
    if (!has_firmware) {
        if (firmware_fallback) {
            // Firmware is optional: missing system applets are served by HLE and synthesized
            // system archives cover fonts, time zones and the firmware version.
            LOG_INFO(Frontend,
                     "Booting without firmware. Missing library applets are served by the HLE "
                     "frontend and synthesized system archives cover fonts, time zones and the "
                     "firmware version. Titles that need a real system program are the exception.");
        } else {
            LOG_WARNING(Frontend,
                        "No system firmware installed and fake_system_applets is off. Games that "
                        "use system applets or services may not boot. This build ships no "
                        "firmware: install your own firmware dump, or enable fake_system_applets "
                        "to use the HLE fallback.");
        }
    }
}
