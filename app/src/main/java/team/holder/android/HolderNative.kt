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
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("holder_jni")
    }.exceptionOrNull()

    private external fun nativeVersion(): String
    private external fun nativeProjectList(dataDir: String, schemaSql: String): String
    private external fun nativeCardList(dataDir: String, schemaSql: String, projectId: String): String

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
}
