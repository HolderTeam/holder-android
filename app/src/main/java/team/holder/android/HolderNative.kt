package team.holder.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HolderProject(
    val projectId: String,
    val name: String,
)

data class HolderCard(
    val cardId: String,
    val projectId: String,
    val title: String,
    val parentCardId: String?,
)

/**
 * Kotlin -> JNI -> C ABI -> libholder boundary. Opens a single native
 * holder_context on first use (see initialize) and keeps it open for the
 * app's lifetime instead of reopening the SQLite store on every call.
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
    private external fun nativeCardGetContent(contextHandle: Long, cardId: String): String
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

    /**
     * Opens the native store (once; safe to call again) and, on first launch,
     * bootstraps a default Home project with a welcome card.
     */
    @Synchronized
    fun initialize(dataDir: File, schemaSql: String, welcomeContent: String) {
        loadError?.let { throw it }

        if (contextHandle == 0L) {
            contextHandle = nativeContextOpen(dataDir.absolutePath, schemaSql)
        }
        nativeEnsureDefaultProject(
            contextHandle,
            DEFAULT_PROJECT_NAME,
            deriveWelcomeTitle(welcomeContent, WELCOME_CARD_TITLE_FALLBACK),
            welcomeContent,
        )
    }

    /** Closes the native store. Safe to call even if it was never opened. */
    @Synchronized
    fun close() {
        if (contextHandle != 0L) {
            nativeContextClose(contextHandle)
            contextHandle = 0L
        }
    }

    fun listProjects(): List<HolderProject> {
        val projects = JSONArray(nativeProjectList(requireContext()))
        return List(projects.length()) { index ->
            val project = projects.getJSONObject(index)
            HolderProject(
                projectId = project.getString("project_id"),
                name = project.getString("name"),
            )
        }
    }

    fun listCards(projectId: String): List<HolderCard> {
        val cards = JSONArray(nativeCardList(requireContext(), projectId))
        return List(cards.length()) { index ->
            val card = cards.getJSONObject(index)
            HolderCard(
                cardId = card.getString("card_id"),
                projectId = card.getString("project_id"),
                title = card.getString("title"),
                parentCardId = card.optStringOrNull("parent_card_id"),
            )
        }
    }

    fun getCardContent(cardId: String): String {
        return nativeCardGetContent(requireContext(), cardId)
    }

    private fun requireContext(): Long {
        val handle = contextHandle
        check(handle != 0L) { "HolderNative.initialize() must be called first" }
        return handle
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)

    /** Mirrors holder-daemon's Bootstrap.cpp: first line, only if it's a markdown heading. */
    private fun deriveWelcomeTitle(content: String, fallback: String): String {
        val firstLine = content.substringBefore('\n').trimStart(' ', '\t', '\r')
        if (firstLine.isEmpty() || firstLine[0] != '#') return fallback
        val title = firstLine.trimStart('#', ' ', '\t')
        return title.ifEmpty { fallback }
    }
}
