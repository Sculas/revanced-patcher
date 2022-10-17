@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.extensions.nullOutputStream
import app.revanced.patcher.util.ProxyBackedClassSet
import app.revanced.patcher.util.dex.DexFile
import brut.androlib.Androlib
import brut.androlib.ApkDecoder
import brut.androlib.meta.MetaInfo
import brut.androlib.meta.UsesFramework
import brut.androlib.options.BuildOptions
import brut.androlib.res.AndrolibResources
import brut.androlib.res.data.ResPackage
import brut.androlib.res.data.ResTable
import brut.androlib.res.decoder.AXmlResourceParser
import brut.androlib.res.decoder.ResAttrDecoder
import brut.androlib.res.decoder.XmlPullStreamDecoder
import brut.androlib.res.xml.ResXmlPatcher
import brut.directory.ExtFile
import brut.directory.ZipUtils
import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.io.MemoryDataStore
import java.io.File

/**
 * The apk file that is to be patched.
 *
 * @param filePath The path to the apk file.
 */
sealed class Apk(filePath: String) {
    /**
     * The apk file.
     */
    open val file = File(filePath)

    /**
     * The patched resources for the [Apk] given by the [app.revanced.patcher.Patcher].
     */
    var resources: File? = null
        internal set

    /**
     * The metadata of the [Apk].
     */
    val packageMetadata = PackageMetadata()

    /**
     *  If the [Apk] has resources.
     */
    open val hasResources: Boolean = true

    /**
     * The split apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    sealed class Split(filePath: String) : Apk(filePath) {

        /**
         * The split apk file which contains language files.
         *
         * @param filePath The path to the apk file.
         */
        class Language(filePath: String) : Split(filePath) {
            internal companion object {
                /**
                 * The name of the language split apk file.
                 */
                const val NAME = "language"
            }

            override fun toString() = NAME
        }

        /**
         * The split apk file which contains libraries.
         *
         * @param filePath The path to the apk file.
         */
        class Library(filePath: String) : Split(filePath) {
            // Library apks do not contain resources
            override val hasResources: Boolean = false

            internal companion object {
                /**
                 * The name of the library split apk file.
                 */
                const val NAME = "library"
            }

            override fun toString() = NAME

            /**
             * Write the resources for [Apk.Split.Library].
             *
             * @param resources Will be ignored.
             * @param patchApk The [Apk] file to write the resources to.
             * @param apkWorkDirectory The directory where the resources are stored.
             * @param metaInfo Will be ignored.
             */
            override fun writeResources(
                resources: AndrolibResources, patchApk: File, apkWorkDirectory: File, metaInfo: MetaInfo
            ) {
                // do not compress libraries for speed, because the patchApk is a temporal file
                val doNotCompress = apkWorkDirectory.listFiles()?.map { it.name }
                ZipUtils.zipFolders(apkWorkDirectory, patchApk, null, doNotCompress)
            }

            /**
             * Read resources for an [Apk] file.
             *
             * @param androlib The [Androlib] instance to decode the resources with.
             * @param extInputFile The [Apk] file.
             * @param outDir The directory to write the resources to.
             * @param resourceTable Will be ignored.
             */
            override fun readResources(
                androlib: Androlib, extInputFile: ExtFile, outDir: File, resourceTable: ResTable?
            ) {
                // only unpack raw files, such as libs
                androlib.decodeRawFiles(extInputFile, outDir, ApkDecoder.DECODE_ASSETS_NONE)
            }
        }

        /**
         * The split apk file which contains assets.
         *
         * @param filePath The path to the apk file.
         */
        class Asset(filePath: String) : Split(filePath) {
            internal companion object {
                /**
                 * The name of the asset split apk file.
                 */
                const val NAME = "asset"
            }

            override fun toString() = NAME
        }
    }

    /**
     * The base apk file that is to be patched.
     *
     * @param filePath The path to the apk file.
     * @see Apk
     */
    class Base(filePath: String) : Apk(filePath) {
        /**
         * Data of the [Base] apk file.
         */
        internal val bytecodeData = BytecodeData()

        /**
         * The patched dex files for the [Base] apk file.
         */
        lateinit var dexFiles: List<DexFile>
            internal set

        override fun toString() = NAME

        internal inner class BytecodeData {
            private val opcodes: Opcodes

            /**
             * The classes and proxied classes of the [Base] apk file.
             */
            val classes = ProxyBackedClassSet(
                MultiDexIO.readDexFile(
                    true,
                    file,
                    Patcher.dexFileNamer,
                    null,
                    null
                ).also { opcodes = it.opcodes }.classes
            )

            /**
             * Write [classes] to [DexFile]s.
             *
             * @return The [DexFile]s.
             */
            internal fun writeDexFiles(): List<DexFile> {
                // Make sure to replace all classes with their proxy
                val classes = classes.also(ProxyBackedClassSet::applyProxies)
                val opcodes = opcodes

                // Create patched dex files
                return mutableMapOf<String, MemoryDataStore>().also {
                    val newDexFile = object : org.jf.dexlib2.iface.DexFile {
                        override fun getClasses() = classes
                        override fun getOpcodes() = opcodes
                    }

                    // Write modified dex files
                    MultiDexIO.writeDexFile(
                        true, -1, // core count
                        it, Patcher.dexFileNamer, newDexFile, DexIO.DEFAULT_MAX_DEX_POOL_SIZE, null
                    )
                }.map {
                    DexFile(it.key, it.value.readAt(0))
                }
            }
        }

        internal companion object {
            /**
             * The name of the base apk file.
             */
            const val NAME = "base"
        }
    }

    /**
     * Decode resources for a [Apk].
     * Note: This function respects the patchers [ResourceDecodingMode].
     *
     * @param options The [PatcherOptions] to decode the resources with.
     * @param mode The [ResourceDecodingMode] to use.
     */
    internal fun decodeResources(options: PatcherOptions, mode: ResourceDecodingMode) {
        val extInputFile = ExtFile(file)
        try {
            val androlib = Androlib(BuildOptions().also { it.setBuildOptions(options) })

            val resourceTable = androlib.getResTable(extInputFile, hasResources)
            when (mode) {
                ResourceDecodingMode.FULL -> {
                    val outDir = File(options.workDirectory).resolve(options.resourcesPath).resolve(toString())
                        .also { it.mkdirs() }

                    readResources(androlib, extInputFile, outDir, resourceTable)

                    // read additional metadata from the resource table
                    with(packageMetadata) {
                        metaInfo.usesFramework = UsesFramework().also { framework ->
                            framework.ids = resourceTable.listFramePackages().map { it.id }.sorted()
                        }

                        // read files to not compress
                        metaInfo.doNotCompress = buildList {
                            androlib.recordUncompressedFiles(extInputFile, this)
                        }
                    }
                }
                ResourceDecodingMode.MANIFEST_ONLY -> {

                    // create decoder for the resource table
                    val decoder = ResAttrDecoder()
                    decoder.currentPackage = ResPackage(resourceTable, 0, null)

                    // create xml parser with the decoder
                    val aXmlParser = AXmlResourceParser()
                    aXmlParser.attrDecoder = decoder

                    // parse package information with the decoder and parser which will set required values in the resource table
                    // instead of decodeManifest another more low level solution can be created to make it faster/better
                    XmlPullStreamDecoder(
                        aXmlParser, AndrolibResources().resXmlSerializer
                    ).decodeManifest(
                        extInputFile.directory.getFileInput("AndroidManifest.xml"), nullOutputStream
                    )
                }
            }

            // read of the resourceTable which is created by reading the manifest file
            with(packageMetadata) {
                resourceTable.currentResPackage.name?.let { packageName = it }
                resourceTable.versionInfo.versionName?.let { packageVersion = it }

                metaInfo.versionInfo = resourceTable.versionInfo
                metaInfo.sdkInfo = resourceTable.sdkInfo
            }
        } finally {
            extInputFile.close()
        }
    }

    /**
     * Read resources for an [Apk] file.
     *
     * @param androlib The [Androlib] instance to decode the resources with.
     * @param extInputFile The [Apk] file.
     * @param outDir The directory to write the resources to.
     * @param resourceTable The [ResTable] to use.
     */
    protected open fun readResources(
        androlib: Androlib, extInputFile: ExtFile, outDir: File, resourceTable: ResTable?
    ) {
        // always decode the manifest file
        androlib.decodeManifestWithResources(extInputFile, outDir, resourceTable)
        androlib.decodeResourcesFull(extInputFile, outDir, resourceTable)
    }


    /**
     * Write resources for a [Apk].
     *
     * @param options The [PatcherOptions] to write the resources with.
     */
    internal fun writeResources(options: PatcherOptions) {
        val packageMetadata = packageMetadata
        val metaInfo = packageMetadata.metaInfo

        val androlibResources = AndrolibResources().also { resources ->
            resources.buildOptions = BuildOptions().also { buildOptions ->
                buildOptions.setBuildOptions(options)
                buildOptions.isFramework = metaInfo.isFrameworkApk
                buildOptions.resourcesAreCompressed = metaInfo.compressionType
                buildOptions.doNotCompress = metaInfo.doNotCompress
            }

            resources.setSdkInfo(metaInfo.sdkInfo)
            resources.setVersionInfo(metaInfo.versionInfo)
            resources.setSharedLibrary(metaInfo.sharedLibrary)
            resources.setSparseResources(metaInfo.sparseResources)
        }

        val workDirectory = File(options.workDirectory)

        // the resulting resource file
        val patchApk = workDirectory
            .resolve(options.patchPath)
            .also { it.mkdirs() }
            .resolve(file.name)
            .also { resources = it }

        val apkWorkDirectory = workDirectory.resolve(options.resourcesPath).resolve(toString())
        writeResources(androlibResources, patchApk, apkWorkDirectory, metaInfo)
    }

    /**
     * Write the resources for [Apk.file].
     *
     * @param resources The [AndrolibResources] to read the framework ids from.
     * @param patchApk The [Apk] file to write the resources to.
     * @param apkWorkDirectory The directory where the resources are stored.
     * @param metaInfo The [MetaInfo] for the [Apk] file.
     */
    protected open fun writeResources(
        resources: AndrolibResources, patchApk: File, apkWorkDirectory: File, metaInfo: MetaInfo
    ) = resources.aaptPackage(
        patchApk,
        apkWorkDirectory.resolve("AndroidManifest.xml")
            .also { ResXmlPatcher.fixingPublicAttrsInProviderAttributes(it) },
        apkWorkDirectory.resolve("res"),
        null,
        null,
        metaInfo.usesFramework.ids.map { id ->
            resources.getFrameworkApk(
                id, metaInfo.usesFramework.tag
            )
        }.toTypedArray()
    )

    private companion object {
        /**
         * Set options for the [Androlib] instance.
         *
         * @param options The [PatcherOptions].
         */
        fun BuildOptions.setBuildOptions(options: PatcherOptions) {
            this.aaptPath = options.aaptPath
            this.useAapt2 = true
            this.frameworkFolderLocation = options.frameworkPath
        }
    }

    /**
     * The type of decoding the resources.
     */
    internal enum class ResourceDecodingMode {
        /**
         * Decode all resources.
         */
        FULL,

        /**
         * Decode the manifest file only.
         */
        MANIFEST_ONLY,
    }

    /**
     * Metadata about an [Apk] file.
     */
    class PackageMetadata {
        /**
         * The [MetaInfo] of the [Apk] file.
         */
        internal val metaInfo: MetaInfo = MetaInfo()

        /**
         * List of [Apk] files which should remain uncompressed.
         */
        val doNotCompress: Collection<String>
            get() = metaInfo.doNotCompress

        /**
         * The package name of the [Apk] file.
         */
        var packageName: String = "unnamed split apk file"
            internal set

        /**
         * The package version of the [Apk] file.
         */
        var packageVersion: String = "0.0.0"
            internal set
    }
}