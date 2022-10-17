package app.revanced.patcher

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.extensions.PatchExtensions.dependencies
import app.revanced.patcher.extensions.PatchExtensions.deprecated
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.fingerprint.method.impl.MethodFingerprint.Companion.resolve
import app.revanced.patcher.patch.*
import app.revanced.patcher.util.VersionReader
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.MultiDexIO
import java.io.File

/**
 * The ReVanced Patcher.
 * @param options The options for the patcher.
 */
class Patcher(private val options: PatcherOptions) {
    private val context = PatcherContext()
    private val logger = options.logger
    private var decodingMode = Apk.ResourceDecodingMode.MANIFEST_ONLY

    companion object {
        @Suppress("SpellCheckingInspection")
        internal val dexFileNamer = BasicDexFileNamer()

        /**
         * The version of the ReVanced Patcher.
         */
        @JvmStatic
        val version = VersionReader.read()
    }

    init {
        // decode manifest file
        logger.info("Decoding manifest file of the base apk file")
        options.inputFiles.base.decodeResources(options, Apk.ResourceDecodingMode.MANIFEST_ONLY)
    }

    /**
     * Add [Patch]es to the patcher.
     * @param patches [Patch]es The patches to add.
     */
    fun addPatches(patches: Iterable<Class<out Patch<Context>>>) {
        /**
         * Fill the cache with the instances of the [Patch]es for later use.
         * Note: Dependencies of the [Patch] will be cached as well.
         */
        fun Class<out Patch<Context>>.isResource() {
            this.also {
                if (!ResourcePatch::class.java.isAssignableFrom(it)) return@also
                // set the mode to decode all resources before running the patches
                decodingMode = Apk.ResourceDecodingMode.FULL
            }.dependencies?.forEach { it.java.isResource() }
        }

        context.patches.addAll(patches.onEach(Class<out Patch<Context>>::isResource))
    }

    /**
     * Add additional dex file container to the patcher.
     * @param files The dex file containers to add to the patcher.
     * @param allowedOverwrites A list of class types that are allowed to be overwritten.
     */
    fun addFiles(
        files: List<File>,
        allowedOverwrites: Iterable<String> = emptyList(),
        callback: (File) -> Unit
    ) {
        val classes = context.bytecodeContext.classes

        for (file in files) {
            var modified = false
            for (classDef in MultiDexIO.readDexFile(true, file, dexFileNamer, null, null).classes) {
                val type = classDef.type

                val index = classes.indexOfFirst { it.type == type }
                if (index == -1) {
                    logger.trace("Merging $type")
                    classes.add(classDef)
                    modified = true

                    continue
                } else if (!allowedOverwrites.contains(type))
                    continue

                logger.trace("Overwriting $type")

                classes[index] = classDef
                modified = true
            }

            if (modified) callback(file)
        }
    }

    /**
     * Execute patches added the patcher.
     *
     * @param stopOnError If true, the patches will stop on the first error.
     * @return A pair of the name of the [Patch] and its [PatchResult].
     */
    fun executePatches(stopOnError: Boolean = false) = sequence {
        /**
         * Execute a [Patch] and its dependencies recursively.
         *
         * @param patchClass The [Patch] to execute.
         * @param executedPatches A map of [Patch]es paired to a boolean indicating their success, to prevent infinite recursion.
         * @return The result of executing the [Patch].
         */
        fun executePatch(
            patchClass: Class<out Patch<Context>>, executedPatches: LinkedHashMap<String, ExecutedPatch>
        ): PatchResult {
            val patchName = patchClass.patchName

            // if the patch has already executed silently skip it
            if (executedPatches.contains(patchName)) {
                if (!executedPatches[patchName]!!.success)
                    return PatchResult.Error("'$patchName' did not succeed previously")

                logger.trace("Skipping '$patchName' because it has already been applied")

                return PatchResult.Success
            }

            // recursively execute all dependency patches
            patchClass.dependencies?.forEach { dependencyClass ->
                val dependency = dependencyClass.java

                executePatch(dependency, executedPatches).also {
                    if (it is PatchResult.Success) return@forEach
                }.let {
                    with(it as PatchResult.Error) {
                        val errorMessage = cause ?: message
                        return PatchResult.Error("'$patchName' depends on '${dependency.patchName}' but the following exception was raised: $errorMessage", it.cause)
                    }
                }
            }

            patchClass.deprecated?.let { (reason, replacement) ->
                logger.warn("'$patchName' is deprecated, reason: $reason")
                if (replacement != null) logger.warn("Use '${replacement.java.patchName}' instead")
            }

            val isResourcePatch = ResourcePatch::class.java.isAssignableFrom(patchClass)
            val patchInstance = patchClass.getDeclaredConstructor().newInstance()

            // TODO: implement this in a more polymorphic way
            val patchContext = if (isResourcePatch) {
                context.resourceContext
            } else {
                context.bytecodeContext.also { context ->
                    (patchInstance as BytecodePatch).fingerprints?.resolve(
                        context,
                        context.classes
                    )
                }
            }

            logger.trace("Executing '$patchName' of type: ${if (isResourcePatch) "resource" else "bytecode"}")

            return try {
                patchInstance.execute(patchContext)
            } catch (patchException: PatchResult.Error) {
                patchException
            } catch (exception: Exception) {
                PatchResult.Error("Unhandled patch exception: ${exception.message}", exception)
            }.also {
                executedPatches[patchName] = ExecutedPatch(patchInstance, it is PatchResult.Success)
            }
        }

        // prevent from decoding the manifest twice if it is not needed
        if (decodingMode == Apk.ResourceDecodingMode.FULL) {
            options.inputFiles.decodeResources(options, Apk.ResourceDecodingMode.FULL).forEach {
                logger.info("Decoding resources for $it apk file")
            }
        }

        logger.trace("Executing all patches")

        val executedPatches = LinkedHashMap<String, ExecutedPatch>() // first is name

        try {
            context.patches.forEach { patch ->
                with(executePatch(patch, executedPatches)) {
                    yield(patch.patchName to this)
                    if (stopOnError && this is PatchResult.Error) return@sequence
                }
            }
            } finally {
                executedPatches.values.reversed().forEach { (patch, _) ->
                    patch.close()
                }
            }
        }

    /**
     * Save the patched dex file.
     *
     * @return The [PatcherResult] of the [Patcher].
     */
    fun save(): PatcherResult {
        if (decodingMode == Apk.ResourceDecodingMode.FULL) {
            options.inputFiles.writeResources(options).forEach {
                logger.info("Writing patched resources for $it apk file")
            }
        }

        with(options.inputFiles.base) {
            logger.info("Writing patched dex files")
            dexFiles = bytecodeData.writeDexFiles()
        }

        // collect the patched files
        with(options.inputFiles) {
            val patchedFiles = splits.toMutableList<Apk>().also { it.add(base) }

            return PatcherResult(patchedFiles)
        }
    }

    private inner class PatcherContext {
        val patches = mutableListOf<Class<out Patch<Context>>>()

        val bytecodeContext = BytecodeContext(options)
        val resourceContext = ResourceContext(options)
    }
}

/**
 * A result of executing a [Patch].
 *
 * @param patchInstance The instance of the [Patch] that was executed.
 * @param success The result of the [Patch].
 */
internal data class ExecutedPatch(val patchInstance: Patch<Context>, val success: Boolean)