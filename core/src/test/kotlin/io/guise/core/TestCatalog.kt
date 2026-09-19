package io.guise.core

import io.guise.core.config.ConfigCodec
import io.guise.core.profile.DeviceCatalog
import java.io.File

/**
 * Locates the device catalog that ships inside the hook layer's assets.
 *
 * The path is searched rather than hardcoded. An earlier version of this file named the
 * module directory literally, and renaming that directory (hook -> xposed) turned it into
 * a path that matched nothing -- which surfaced as all twenty-two tests failing at once
 * instead of as one clear "catalog not found". Searching the sibling modules makes the
 * suite survive a rename.
 */
object TestCatalog {

    private const val RELATIVE = "src/main/assets/catalog.json"

    fun file(): File {
        // Cheap common case first: the module sitting next to :core.
        listOf(File("../xposed/$RELATIVE"), File("xposed/$RELATIVE"))
            .firstOrNull(File::exists)
            ?.let { return it }

        val root = File("..").absoluteFile
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { File(it, RELATIVE) }
            ?.firstOrNull(File::exists)
            ?: error(
                "catalog.json not found under any sibling module (looked in ${root.path}). " +
                    "cwd=${File(".").absolutePath}",
            )
    }

    fun load(): DeviceCatalog = ConfigCodec.decodeCatalog(file().readText())
}
