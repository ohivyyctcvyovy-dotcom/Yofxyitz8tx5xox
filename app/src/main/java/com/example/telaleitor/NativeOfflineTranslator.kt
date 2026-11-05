package com.example.telaleitor

import android.content.Context

internal object NativeOfflineTranslator {
    @Volatile private var initialized = false
    init { System.loadLibrary("aurora_translator") }
    @Synchronized fun initialize(context: Context): Boolean {
        if (initialized) return true
        val index = context.assets.open("index_v7.json").bufferedReader().use { it.readText() }
        initialized = nativeInit(index, context.filesDir.absolutePath + "/aurora-translator")
        return initialized
    }
    external fun nativeInit(indexJson: String, baseDir: String): Boolean
    external fun nativeInstallLanguage(code: String): String
    external fun nativeTranslate(from: String, text: String): String
}
