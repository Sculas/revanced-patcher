package app.revanced.patcher.apk

import app.revanced.patcher.PatcherOptions

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param splits The apk files of type [Apk.Split].
 */
data class SplitApkFile(val base: Apk.Base, val splits: List<Apk.Split> = emptyList()) {
    /**
     * Write resources for the files in [SplitApkFile].
     *
     * @param options The [PatcherOptions] to write the resources with.
     * @return A sequence of the [Apk] files which resources are being written.
     */
    internal fun writeResources(options: PatcherOptions) = sequence {
        with(base) {
            yield(this)
            writeResources(options)
        }

        splits.forEach {
            yield(it)
            it.writeResources(options)
        }
    }

    /**
     * Decode resources for the files in [SplitApkFile].
     *
     * @param options The [PatcherOptions] to decode the resources with.
     * @param mode The [Apk.ResourceDecodingMode] to use.
     * @return A sequence of the [Apk] files which resources are being decoded.
     */
    internal fun decodeResources(options: PatcherOptions, mode: Apk.ResourceDecodingMode) = sequence {
        with(base) {
            yield(this)
            decodeResources(options, mode)
        }

        splits.forEach {
            yield(it)
            it.decodeResources(options, mode)
        }
    }
}