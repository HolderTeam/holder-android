package team.holder.android

import org.json.JSONArray
import java.io.File

data class HolderSnapshot(
    val coreVersion: String,
    val projectCount: Int,
    val cardCount: Int,
    val status: String,
)

object HolderNative {
    private const val DEFAULT_PROJECT_NAME = "Home"
    private const val WELCOME_CARD_TITLE_FALLBACK = "Welcome"

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("holder_jni")
    }.exceptionOrNull()

    private external fun nativeVersion(): String
    private external fun nativeProjectList(dataDir: String, schemaSql: String): String
    private external fun nativeCardList(dataDir: String, schemaSql: String, projectId: String): String
    private external fun nativeProjectCreate(
        dataDir: String,
        schemaSql: String,
        name: String,
        rootPath: String?,
        privacyMode: String?,
    ): String
    private external fun nativeCardCreate(
        dataDir: String,
        schemaSql: String,
        projectId: String,
        title: String,
        content: String?,
        parentCardId: String?,
    ): String
    private external fun nativeEnsureDefaultProject(
        dataDir: String,
        schemaSql: String,
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
            ensureDefaultProject(dataDir.absolutePath, schemaSql, welcomeContent)

            val projectsJson = nativeProjectList(dataDir.absolutePath, schemaSql)
            val projects = JSONArray(projectsJson)
            var cardCount = 0
            for (index in 0 until projects.length()) {
                val projectId = projects.getJSONObject(index).getString("project_id")
                cardCount += JSONArray(nativeCardList(dataDir.absolutePath, schemaSql, projectId)).length()
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

    /** On first launch (no projects yet), creates a default Home project and a welcome card. */
    private fun ensureDefaultProject(dataDir: String, schemaSql: String, welcomeContent: String) {
        nativeEnsureDefaultProject(
            dataDir,
            schemaSql,
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
