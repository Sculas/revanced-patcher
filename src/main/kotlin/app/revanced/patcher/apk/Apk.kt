@file:Suppress("MemberVisibilityCanBePrivate")

package app.revanced.patcher.apk

import app.revanced.patcher.DomFileEditor
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.extensions.nullOutputStream
import app.revanced.patcher.util.ProxyBackedClassSet
import app.revanced.patcher.util.dex.DexFile
import brut.androlib.Androlib
import brut.androlib.AndrolibException
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
import org.w3c.dom.Element
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo

/**
 * The apk file that is to be patched.
 *
 * @param filePath The path to the apk file.
 */
sealed class Apk(filePath: String) {
    /**
     * Get the resource directory of the apk file.
     *
     * @param options The patcher context to resolve the resource directory for the [Apk] file.
     * @return The resource directory of the [Apk] file.
     */
    protected fun getResourceDirectory(options: PatcherOptions) = options.resourceDirectory.resolve(toString())

    /**
     * Get a file from the resources of the [Apk] file.
     *
     * @param path The path of the resource file.
     * @param options The patcher context to resolve the resource directory for the [Apk] file.
     * @return A [File] instance for the resource file.
     */
    internal fun getFile(path: String, options: PatcherOptions) = getResourceDirectory(options).resolve(path)

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
            override fun toString() = "language"
        }

        /**
         * The split apk file which contains libraries.
         *
         * @param filePath The path to the apk file.
         */
        class Library(filePath: String) : Split(filePath) {

            override fun toString() = "library"

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
                // do not compress libraries (.so) for speed, because the patchApk is a temporal file
                ZipUtils.zipFolders(apkWorkDirectory, patchApk, null, listOf("so"))

                // write the patchApk file containing the manifest file
                apkWorkDirectory.resolve(patchApk.name).also { manifestPatchApk ->
                    super.writeResources(resources, manifestPatchApk, apkWorkDirectory, metaInfo)
                }.let { manifestPatchApk ->
                    // copy AndroidManifest.xml from manifestPatchApk to patchApk
                    fun File.createFs() = FileSystems.newFileSystem(toPath(), null as ClassLoader?)
                    manifestPatchApk.createFs().use { manifestPatchApkFs ->
                        patchApk.createFs().use { patchApkFs ->
                            // delete AndroidManifest.xml from patchApk and copy it from manifestPatchApk
                            patchApkFs.getPath("/AndroidManifest.xml").also { manifestPath ->
                                patchApkFs.provider().delete(manifestPath)
                                manifestPatchApkFs.getPath("/AndroidManifest.xml").copyTo(manifestPath)
                            }
                        }
                    }
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
            override fun readResources(
                androlib: Androlib, extInputFile: ExtFile, outDir: File, resourceTable: ResTable?
            ) {
                // decode the manifest without looking up attribute references because there is no resources.arsc file
                androlib.decodeManifestFull(extInputFile, outDir, resourceTable)
                androlib.decodeRawFiles(extInputFile, outDir, ApkDecoder.DECODE_ASSETS_NONE)
            }
        }

        /**
         * The split apk file which contains assets.
         *
         * @param filePath The path to the apk file.
         */
        class Asset(filePath: String) : Split(filePath) {
            override fun toString() = "asset"
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

        override fun toString() = "base"

        /**
         * Move resources from an [ApkBundle] to the [Base] [Apk] file.
         *
         * @param apkBundle The [ApkBundle] move resources from.
         * @param options The [PatcherOptions] of the [Patcher].
         */
        internal fun moveResources(apkBundle: ApkBundle, options: PatcherOptions) {
            val workDirectory = options.workDirectory.resolve("merged").also(File::mkdirs)
            val workDirectoryPath = workDirectory.toPath()

            val toResourceDirectory = getResourceDirectory(options)
            val toResourceDirectoryPath = toResourceDirectory.toPath()

            apkBundle.split?.let {
                // remove isSplitRequired attribute
                DomFileEditor(toResourceDirectory.resolve("AndroidManifest.xml")).use { editor ->
                    val applicationNode = editor.file.getElementsByTagName("application").item(0) as Element
                    applicationNode.removeAttribute("android:isSplitRequired")
                }

                // merge resources
                it.all.onEach { split ->
                    // move split resources to work directory
                    Files.copy(
                        split.getResourceDirectory(options).toPath(),
                        workDirectoryPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }.also {
                    // move base resources to work directory and overwrite split resources
                    Files.copy(
                        toResourceDirectoryPath,
                        workDirectoryPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }.onEach { split ->
                    // fix resource references
                    when (split) {
                        is Split.Asset,
                        is Split.Language -> {
                            TODO("Find APKTOOL_DUMMY references in base resources and replace them with the split resources")
                        }
                        is Split.Library -> {
                            TODO("Find APKTOOL_DUMMY references in base resources and replace them with the split resources")
                        }
                    }
                }.also {
                    // move merged resources to base
                    Files.move(
                        workDirectoryPath,
                        toResourceDirectoryPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        }

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

            val resourceTable = try {
                androlib.getResTable(extInputFile, this !is Split.Library)
            } catch (exception: AndrolibException) {
                throw ApkException.Decode("Failed to get the resource table", exception)
            }

            when (mode) {
                ResourceDecodingMode.FULL -> {
                    val outDir = getResourceDirectory(options)
                        .also { it.mkdirs() }

                    try {
                        readResources(androlib, extInputFile, outDir, resourceTable)
                    } catch (exception: AndrolibException) {
                        throw ApkException.Decode("Failed to decode resources for $this", exception)
                    }

                    // read additional metadata from the resource table
                    with(packageMetadata) {
                        metaInfo.usesFramework = UsesFramework().also { framework ->
                            framework.ids = resourceTable.listFramePackages().map { it.id }.sorted()
                        }

                        // read files to not compress
                        metaInfo.doNotCompress = buildList {
                            androlib.recordUncompressedFiles(extInputFile, this)
                        }.takeIf { it.isNotEmpty() } // uncomment this line to know why it is required
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
                    with(
                        XmlPullStreamDecoder(
                            aXmlParser, AndrolibResources().resXmlSerializer
                        )
                    ) {
                        try {
                            decodeManifest(
                                extInputFile.directory.getFileInput("AndroidManifest.xml"), nullOutputStream
                            )
                        } catch (exception: AndrolibException) {
                            throw ApkException.Decode("Failed to decode the manifest file for $this", exception)
                        }
                    }
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
        val apkWorkDirectory = getResourceDirectory(options).also {
            if (!it.exists()) throw ApkException.Write.ResourceDirectoryNotFound
        }

        // the resulting resource file
        val patchApk = options.patchDirectory
            .also { it.mkdirs() }
            .resolve(file.name)
            .also { resources = it }

        val packageMetadata = packageMetadata
        val metaInfo = packageMetadata.metaInfo

        with(AndrolibResources().also { resources ->
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
        }) {
            writeResources(this, patchApk, apkWorkDirectory, metaInfo)
        }
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
        apkWorkDirectory.resolve("res").takeUnless { this is Split.Library },
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
        val doNotCompress: Collection<String>?
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

    sealed class ApkException(message: String, throwable: Throwable? = null) : Exception(message, throwable) {
        class Decode(message: String, throwable: Throwable? = null) : ApkException(message, throwable)
        open class Write(message: String, throwable: Throwable? = null) : ApkException(message, throwable) {
            object ResourceDirectoryNotFound : Write("Failed to find the resource directory")
        }
    }
}