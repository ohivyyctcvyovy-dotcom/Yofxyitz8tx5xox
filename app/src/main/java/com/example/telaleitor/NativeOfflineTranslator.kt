package com.example.telaleitor

import android.content.Context

internal object NativeOfflineTranslator {
    @Volatile private var initialized = false

    init {
        System.loadLibrary("aurora_translator")
    }

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initialized) return true
        val index = context.assets.open("index_v7.json").bufferedReader().use { it.readText() }
        initialized = nativeInit(index, context.filesDir.absolutePath + "/aurora-translator")
        return initialized
    }

    // Kotlin-facing wrappers used by OfflineTranslationClient.
    fun installLanguage(code: String): String = nativeInstallLanguage(code)

    fun translate(from: String, text: String): String = nativeTranslate(from, text)

    private external fun nativeInit(indexJson: String, baseDir: String): Boolean
    private external fun nativeInstallLanguage(code: String): String
    private external fun nativeTranslate(from: String, text: String): String
}
