package team.holder.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HolderSnapshot(
    val coreVersion: String,
    val projectCount: Int,
    val cardCount: Int,
    val status: String,
)

object HolderNative {
    private const val DEFAULT_PROJECT_NAME = "Home"
    private const val WELCOME_CARD_TITLE = "Welcome"
    private const val WELCOME_CARD_CONTENT = "# Welcome to Holder\n\n" +
        "This is your first card. Edit or delete it to get started.\n"

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

    fun snapshot(dataDir: File, schemaSql: String): HolderSnapshot {
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
            ensureDefaultProject(dataDir.absolutePath, schemaSql)

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
    private fun ensureDefaultProject(dataDir: String, schemaSql: String) {
        val projects = JSONArray(nativeProjectList(dataDir, schemaSql))
        if (projects.length() > 0) return

        val project = JSONObject(
            nativeProjectCreate(dataDir, schemaSql, DEFAULT_PROJECT_NAME, null, null)
        )
        val projectId = project.getString("project_id")
        nativeCardCreate(dataDir, schemaSql, projectId, WELCOME_CARD_TITLE, WELCOME_CARD_CONTENT, null)
    }
}
