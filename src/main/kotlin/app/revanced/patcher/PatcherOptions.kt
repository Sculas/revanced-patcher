package app.revanced.patcher

import app.revanced.patcher.apk.SplitApkFile
import app.revanced.patcher.logging.Logger

/**
 * Options for the [Patcher].
 * @param inputFiles The input files (usually apk files).
 * @param workDirectory Directory to work in.
 * @param aaptPath Optional path to a custom aapt binary.
 * @param frameworkPath Optional path to a custom framework folder.
 * @param logger Custom logger implementation for the [Patcher].
 */
data class PatcherOptions(
    internal val inputFiles: SplitApkFile,
    internal val workDirectory: String,
    internal val aaptPath: String = "",
    internal val frameworkPath: String? = null,
    internal val logger: Logger = Logger.Nop
) {
    // relative paths to PatcherOptions.workDirectory
    internal val resourcesPath = "resources"
    internal val patchPath = "patch"
}