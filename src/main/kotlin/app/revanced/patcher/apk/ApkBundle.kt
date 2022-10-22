package app.revanced.patcher.apk

import app.revanced.patcher.PatcherOptions

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param splits The [Apk.Split] files.
 */
data class ApkBundle(
    val base: Apk.Base,
    val splits: Split? = null
) {

    /**
     * Class for [Apk.Split].
     *
     * @param library The apk file of type [Apk.Base].
     * @param asset The apk file of type [Apk.Base].
     * @param language The apk file of type [Apk.Base].
     */
    data class Split(
        val library: Apk.Split.Library? = null,
        val asset: Apk.Split.Asset? = null,
        val language: Apk.Split.Language? = null,
    ) : Iterable<Apk.Split> {
        private val splits = mutableListOf<Apk.Split>().also { splitsList ->
            fun addNotNull(apk: Apk.Split?) = apk?.let { splitsList.add(apk) }
            listOf(library, asset, language).forEach(::addNotNull)
        }

        override fun iterator() = splits.iterator()
    }

    /**
     * Write resources for the files in [ApkBundle].
     *
     * @param options The [PatcherOptions] to write the resources with.
     * @return A sequence of the [Apk] files which resources are being written.
     */
    internal fun writeResources(options: PatcherOptions) = sequence {
        with(base) {
            yield(SplitApkResult.Write(this))
            writeResources(options)
        }

        splits?.forEach {
            with(it) {
                var exception: Apk.ApkException.Write? = null

                try {
                    writeResources(options)
                } catch (writeException: Apk.ApkException.Write) {
                    exception = writeException
                }

                yield(SplitApkResult.Write(this, exception))
            }
        }
    }

    /**
     * Decode resources for the files in [ApkBundle].
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

        splits?.forEach {
            yield(it)
            it.decodeResources(options, mode)
        }
    }

    sealed class SplitApkResult(val apk: Apk, val exception: Apk.ApkException? = null) {
        class Write(apk: Apk, exception: Apk.ApkException.Write? = null) : SplitApkResult(apk, exception)
    }
}