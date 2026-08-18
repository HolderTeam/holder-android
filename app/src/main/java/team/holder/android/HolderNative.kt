package team.holder.android

object HolderNative {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("holder_jni")
    }.exceptionOrNull()

    private external fun nativeVersion(): String

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
}
