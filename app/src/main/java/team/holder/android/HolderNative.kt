package team.holder.android

import org.json.JSONArray
import java.io.File

data class HolderSnapshot(
    val coreVersion: String,
    val projectCount: Int,
    val cardCount: Int,
    val status: String,
)

/**
 * Kotlin -> JNI -> C ABI -> libholder boundary. Opens a single native
 * holder_context on first use and keeps it open for the app's lifetime
 * instead of reopening the SQLite store on every call.
 */
object HolderNative {
    private const val DEFAULT_PROJECT_NAME = "Home"
    private const val WELCOME_CARD_TITLE_FALLBACK = "Welcome"

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("holder_jni")
    }.exceptionOrNull()

    @Volatile
    private var contextHandle: Long = 0L

    private external fun nativeVersion(): String
    private external fun nativeContextOpen(dataDir: String, schemaSql: String): Long
    private external fun nativeContextClose(contextHandle: Long)
    private external fun nativeProjectList(contextHandle: Long): String
    private external fun nativeCardList(contextHandle: Long, projectId: String): String
    private external fun nativeProjectCreate(
        contextHandle: Long,
        name: String,
        rootPath: String?,
        privacyMode: String?,
    ): String
    private external fun nativeCardCreate(
        contextHandle: Long,
        projectId: String,
        title: String,
        content: String?,
        parentCardId: String?,
    ): String
    private external fun nativeEnsureDefaultProject(
        contextHandle: Long,
        name: String,
        welcomeTitle: String,
        welcomeContent: String?,
    ): String

    fun version(): String {
        loadError?.let {
            return "native load failed: ${it.message ?: it::class.java.simpleName}"
        }

        return runCatching {
            nativeVersion()
        }.getOrElse {
            "native call failed: ${it.message ?: it::class.java.simpleName}"
        }
    }

    fun snapshot(dataDir: File, schemaSql: String, welcomeContent: String): HolderSnapshot {
        val coreVersion = version()
        loadError?.let {
            return HolderSnapshot(
                coreVersion = coreVersion,
                projectCount = 0,
                cardCount = 0,
                status = "Native load failed: ${it.message ?: it::class.java.simpleName}",
            )
        }

        return runCatching {
            val handle = ensureContextOpen(dataDir, schemaSql)
            ensureDefaultProject(handle, welcomeContent)

            val projects = JSONArray(nativeProjectList(handle))
            var cardCount = 0
            for (index in 0 until projects.length()) {
                val projectId = projects.getJSONObject(index).getString("project_id")
                cardCount += JSONArray(nativeCardList(handle, projectId)).length()
            }

            HolderSnapshot(
                coreVersion = coreVersion,
                projectCount = projects.length(),
                cardCount = cardCount,
                status = "Opened native Holder store",
            )
        }.getOrElse {
            HolderSnapshot(
                coreVersion = coreVersion,
                projectCount = 0,
                cardCount = 0,
                status = "Native store failed: ${it.message ?: it::class.java.simpleName}",
            )
        }
    }

    /** Opens the native store on first call; later calls reuse the same context. */
    @Synchronized
    private fun ensureContextOpen(dataDir: File, schemaSql: String): Long {
        if (contextHandle == 0L) {
            contextHandle = nativeContextOpen(dataDir.absolutePath, schemaSql)
        }
        return contextHandle
    }

    /** Closes the native store. Safe to call even if it was never opened. */
    @Synchronized
    fun close() {
        if (contextHandle != 0L) {
            nativeContextClose(contextHandle)
            contextHandle = 0L
        }
    }

    /** On first launch (no projects yet), creates a default Home project and a welcome card. */
    private fun ensureDefaultProject(contextHandle: Long, welcomeContent: String) {
        nativeEnsureDefaultProject(
            contextHandle,
            DEFAULT_PROJECT_NAME,
            deriveWelcomeTitle(welcomeContent, WELCOME_CARD_TITLE_FALLBACK),
            welcomeContent,
        )
    }

    /** Mirrors holder-daemon's Bootstrap.cpp: first line, only if it's a markdown heading. */
    private fun deriveWelcomeTitle(content: String, fallback: String): String {
        val firstLine = content.substringBefore('\n').trimStart(' ', '\t', '\r')
        if (firstLine.isEmpty() || firstLine[0] != '#') return fallback
        val title = firstLine.trimStart('#', ' ', '\t')
        return title.ifEmpty { fallback }
    }
}
