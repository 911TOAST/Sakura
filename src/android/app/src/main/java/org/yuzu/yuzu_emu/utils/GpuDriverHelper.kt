// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.IOException
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import java.io.FileNotFoundException
import java.util.zip.ZipException
import java.util.zip.ZipFile

object GpuDriverHelper {
    private const val META_JSON_FILENAME = "meta.json"
    private var fileRedirectionPath: String? = null
    var driverInstallationPath: String? = null
    internal var hookLibPath: String? = null

    val driverStoragePath get() = DirectoryInitialization.userDirectory!! + "/gpu_drivers/"

    fun initializeFreedrenoConfigEarly() {
        NativeFreedrenoConfig.setFreedrenoBasePath(YuzuApplication.appContext.cacheDir.absolutePath)
        NativeFreedrenoConfig.initializeFreedrenoConfig()
        NativeFreedrenoConfig.reloadFreedrenoConfig()
    }

    fun initializeDriverParameters() {
        try {
            // Initialize the file redirection directory.
            fileRedirectionPath = YuzuApplication.appContext
                .getExternalFilesDir(null)!!.canonicalPath + "/gpu/vk_file_redirect/"

            // Initialize the driver installation directory.
            driverInstallationPath = YuzuApplication.appContext
                .filesDir.canonicalPath + "/gpu_driver/"
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        initializeDirectories()
        hookLibPath = YuzuApplication.appContext.applicationInfo.nativeLibraryDir + "/"
        NativeFreedrenoConfig.reloadFreedrenoConfig()

        // Initialize GPU driver.
        NativeLibrary.initializeGpuDriver(
            hookLibPath,
            driverInstallationPath,
            installedCustomDriverData.libraryName,
            fileRedirectionPath
        )
    }

    fun getDrivers(): MutableList<Pair<String, GpuDriverMetadata>> {
        val driverZips = File(driverStoragePath).listFiles()
        val drivers: MutableList<Pair<String, GpuDriverMetadata>> =
            driverZips
                ?.mapNotNull {
                    val metadata = getMetadataFromZip(it)
                    metadata.name?.let { _ -> Pair(it.path, metadata) }
                }
                ?.sortedByDescending { it: Pair<String, GpuDriverMetadata> -> it.second.name }
                ?.distinct()
                ?.toMutableList() ?: mutableListOf()
        return drivers
    }

    fun installDefaultDriver() {
        // Removing the installed driver will result in the backend using the default system driver.
        File(driverInstallationPath!!).deleteRecursively()
        initializeDriverParameters()
    }

    fun copyDriverToInternalStorage(driverUri: Uri): Boolean {
        // Ensure we have directories.
        initializeDirectories()

        // Copy the zip file URI to user data
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false

        // Validate driver
        val metadata = getMetadataFromZip(copiedFile)
        if (metadata.name == null || metadata.libraryName.isNullOrBlank()) {
            copiedFile.delete()
            return false
        }

        if (metadata.minApi > Build.VERSION.SDK_INT) {
            copiedFile.delete()
            return false
        }
        return true
    }

    /**
     * Copies driver zip into user data directory so that it can be exported along with
     * other user data and also unzipped into the installation directory
     */
    fun installCustomDriver(driverUri: Uri): Boolean {
        // Revert to system default in the event the specified driver is bad.
        installDefaultDriver()

        // Ensure we have directories.
        initializeDirectories()

        // Copy the zip file URI to user data
        val copiedFile =
            FileUtil.copyUriToInternalStorage(driverUri, driverStoragePath) ?: return false

        // Validate driver
        val metadata = getMetadataFromZip(copiedFile)
        if (metadata.name == null || metadata.libraryName.isNullOrBlank()) {
            copiedFile.delete()
            return false
        }

        if (metadata.minApi > Build.VERSION.SDK_INT) {
            copiedFile.delete()
            return false
        }

        // Unzip the driver.
        try {
            FileUtil.unzipToInternalStorage(
                copiedFile.path,
                File(driverInstallationPath!!)
            )
        } catch (e: Exception) {
            installDefaultDriver()
            return false
        }

        if (!runCatching { normalizeAndValidateDriverInstallation() }.getOrDefault(false)) {
            installDefaultDriver()
            return false
        }

        // Initialize the driver parameters.
        initializeDriverParameters()

        return true
    }

    /**
     * Unzips driver into installation directory
     */
    fun installCustomDriver(driver: File): Boolean {
        // Revert to system default in the event the specified driver is bad.
        installDefaultDriver()

        // Ensure we have directories.
        initializeDirectories()

        // Validate driver
        val metadata = getMetadataFromZip(driver)
        if (metadata.name == null || metadata.libraryName.isNullOrBlank() ||
            metadata.minApi > Build.VERSION.SDK_INT
        ) {
            driver.delete()
            return false
        }

        // Unzip the driver to the private installation directory
        try {
            FileUtil.unzipToInternalStorage(
                driver.path,
                File(driverInstallationPath!!)
            )
        } catch (e: Exception) {
            installDefaultDriver()
            return false
        }

        if (!runCatching { normalizeAndValidateDriverInstallation() }.getOrDefault(false)) {
            installDefaultDriver()
            driver.delete()
            return false
        }

        // Initialize the driver parameters.
        initializeDriverParameters()

        return true
    }

    private fun normalizeAndValidateDriverInstallation(): Boolean {
        val root = File(driverInstallationPath ?: return false).canonicalFile
        val metadataFile = root.walkTopDown().firstOrNull {
            it.isFile && it.name.equals(META_JSON_FILENAME, ignoreCase = true)
        } ?: return false
        val metadata = GpuDriverMetadata(metadataFile)
        val libraryName = metadata.libraryName?.takeIf {
            it.isNotBlank() && it == File(it).name && it != "." && it != ".."
        } ?: return false
        val library = root.walkTopDown().firstOrNull { it.isFile && it.name == libraryName }
            ?: return false

        if (metadataFile.parentFile != root &&
            !metadataFile.copyTo(File(root, META_JSON_FILENAME), true).exists()
        ) {
            return false
        }
        if (library.parentFile != root) {
            library.parentFile?.listFiles()
                ?.filter { it.isFile && it.extension == "so" }
                ?.forEach { file ->
                    if (file.parentFile != root &&
                        !file.copyTo(File(root, file.name), true).exists()
                    ) {
                        return false
                    }
                }
        }
        return File(root, META_JSON_FILENAME).isFile && File(root, libraryName).isFile
    }

    /**
     * Takes in a zip file and reads the meta.json file for presentation to the UI
     *
     * @param driver Zip containing driver and meta.json file
     * @return A non-null [GpuDriverMetadata] instance that may have null members
     */
    fun getMetadataFromZip(driver: File): GpuDriverMetadata {
        if (!driver.exists()) {
            return GpuDriverMetadata()
        }

        try {
            ZipFile(driver).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.substringAfterLast('/')
                            .equals(META_JSON_FILENAME, ignoreCase = true)
                    ) {
                        zf.getInputStream(entry).use {
                            return GpuDriverMetadata(it, entry.size)
                        }
                    }
                }
            }
        } catch (_: ZipException) {
        } catch (_: FileNotFoundException) {
        }
        return GpuDriverMetadata()
    }

    external fun supportsCustomDriverLoading(): Boolean

    external fun getSystemDriverInfo(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String = GpuDriverHelper.hookLibPath!!
    ): Array<String>?

    external fun getGpuModel(
        surface: Surface = Surface(SurfaceTexture(true)),
        hookLibPath: String
    ): String?

    /**
     * Startup self-heal for a broken or incompatible custom driver.
     *
     * A driver whose zip was deleted, or that was extracted from a package this device cannot
     * run, leaves an unusable library inside the private installation dir. Because
     * initializeDriverParameters() hooks that library on every launch, the emulator then crashes
     * natively on the first driver query (e.g. opening Settings) and no amount of editing
     * config.ini or deleting the visible gpu_drivers folder repairs it - only clearing app data
     * did, which meant reinstalling the app.
     *
     * Called from Application.onCreate before the hook is installed: fall back to the system
     * driver whenever the installed metadata is unreadable or its library is missing.
     */
    fun validateOrResetInstalledDriver(): Boolean {
        val metadata = installedCustomDriverData
        val libraryName = metadata.libraryName?.takeIf {
            it.isNotBlank() && it == File(it).name && it != "." && it != ".."
        }
        if (metadata.name != null && libraryName != null) {
            if (File(driverInstallationPath, libraryName).isFile) {
                return true
            }
            Log.error(
                "[GpuDriverHelper] Installed driver '${metadata.name}' is missing library " +
                    "$libraryName; reverting to the system driver"
            )
        } else {
            Log.info(
                "[GpuDriverHelper] No valid custom driver installed; using the system driver"
            )
        }
        if (libraryName != null || File(driverInstallationPath + META_JSON_FILENAME).isFile) {
            installDefaultDriver()
        } else {
            initializeDriverParameters()
        }
        // Also drop the dangling pointer to a driver zip that no longer exists, so the next
        // launch install doesn't fall back silently on a missing file.
        val selectedPath = StringSetting.DRIVER_PATH.getString()
        if (selectedPath.isNotBlank() && !File(selectedPath).exists()) {
            StringSetting.DRIVER_PATH.setString("")
            NativeConfig.saveGlobalConfig()
        }
        return false
    }

    /**
     * The driver packages the fetcher surfaces include Adreno-only builds (Turnip, Freedreno).
     * Extracting and hooking one of those on a Mali device produced the "settings crash" reports:
     * the library is structurally valid, so it installs and hooks fine, then dies natively the
     * first time a Vulkan instance is created. Refuse those packages instead of installing them.
     */
    private val ADRENO_ONLY_DRIVER_TOKENS = listOf(
        "turnip", "freedreno", "adreno", "msm", "kgsl", "qcom", "qualcomm"
    )

    fun isDriverCompatibleWithDevice(metadata: GpuDriverMetadata): Boolean {
        if (metadata.name.isNullOrBlank()) {
            return false
        }
        if (!isAdrenoGpu()) {
            val haystack = listOf(metadata.name ?: "", metadata.vendor ?: "", metadata.description ?: "")
                .joinToString(" ").lowercase()
            if (ADRENO_ONLY_DRIVER_TOKENS.any { haystack.contains(it) }) {
                return false
            }
        }
        return true
    }

    fun isAdrenoGpu(): Boolean {
        // The adrenotools hook loads an ICD on any 64-bit device, so "supports custom driver
        // loading" is true on Mali too and used to show the Adreno/Freedreno surfaces there.
        // The kernel GPU nodes identify the actual vendor.
        if (File("/sys/class/kgsl/kgsl-3d0").exists()) {
            return true
        }
        if (File("/sys/class/misc/mali0").exists()) {
            return false
        }
        return try {
            supportsCustomDriverLoading()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Custom drivers load through the adrenotools dlopen hook, which is generic: the Adreno-only
     * part is the GSL/kernel patching, not the driver swap itself. Any 64-bit device can therefore
     * run a replacement ICD, Mali included.
     */
    fun supportsCustomDriver(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    /** The PanVK package sitting in driver storage, if the user imported one. */
    fun findPanVkZip(): File? =
        File(driverStoragePath).listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile && it.name.startsWith("PanVK", ignoreCase = true) &&
                    it.extension.equals("zip", ignoreCase = true)
            }
            ?.filter { candidate ->
                val metadata = getMetadataFromZip(candidate)
                metadata.name?.contains("PanVK", ignoreCase = true) == true &&
                    !metadata.libraryName.isNullOrBlank() &&
                    metadata.minApi <= Build.VERSION.SDK_INT
            }
            ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })

    fun isPanVkInstalled(): Boolean {
        val installationRoot = driverInstallationPath ?: return false
        val metadata = installedCustomDriverData
        return metadata.name?.contains("PanVK", ignoreCase = true) == true &&
            metadata.libraryName?.let { File(installationRoot, it).isFile } == true
    }

    /**
     * Installs (or removes) the PanVK ICD as the active custom driver. Copies to a temp file
     * first: installCustomDriver deletes the file it is handed when validation fails, and we do
     * not want to eat the user's stored package.
     */
    fun setPanVkEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            installDefaultDriver()
            StringSetting.DRIVER_PATH.setString("")
            NativeConfig.saveGlobalConfig()
            return true
        }
        val stored = findPanVkZip() ?: return false
        val staging = File(YuzuApplication.appContext.cacheDir, stored.name)
        runCatching { stored.copyTo(staging, overwrite = true) }.getOrNull() ?: return false
        if (!installCustomDriver(staging)) {
            installDefaultDriver()
            StringSetting.DRIVER_PATH.setString("")
            NativeConfig.saveGlobalConfig()
            return false
        }
        StringSetting.DRIVER_PATH.setString(stored.path)
        NativeConfig.saveGlobalConfig()
        return true
    }

    // Parse the custom driver metadata to retrieve the name.
    val installedCustomDriverData: GpuDriverMetadata
        get() = GpuDriverMetadata(File(driverInstallationPath + META_JSON_FILENAME))

    val customDriverSettingData: GpuDriverMetadata
        get() = getMetadataFromZip(File(StringSetting.DRIVER_PATH.getString()))

    fun initializeDirectories() {
        // Ensure the file redirection directory exists.
        val fileRedirectionDir = File(fileRedirectionPath!!)
        if (!fileRedirectionDir.exists()) {
            fileRedirectionDir.mkdirs()
        }
        // Ensure the driver installation directory exists.
        val driverInstallationDir = File(driverInstallationPath!!)
        if (!driverInstallationDir.exists()) {
            driverInstallationDir.mkdirs()
        }
        // Ensure the driver storage directory exists
        val driverStorageDirectory = File(driverStoragePath)
        if (!driverStorageDirectory.exists()) {
            driverStorageDirectory.mkdirs()
        }
    }

    /**
     * Checks if a driver zip with the given filename is already present and valid in the
     * internal driver storage directory. Validation requires a readable meta.json with a name.
     */
    fun isDriverZipInstalledByName(fileName: String): Boolean {
        // Normalize separators in case upstream sent a path
        val baseName = fileName.substringAfterLast('/')
            .substringAfterLast('\\')
        val candidate = File("$driverStoragePath$baseName")
        if (!candidate.exists() || candidate.length() == 0L) return false
        val metadata = getMetadataFromZip(candidate)
        return metadata.name != null
    }
}
