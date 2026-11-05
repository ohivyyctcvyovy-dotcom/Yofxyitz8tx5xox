package com.example.telaleitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError

/**
 * Connects to Offline Translator's on-device translation service.
 * The captured text is sent only over Android Binder IPC on this device.
 */
class OfflineTranslationClient(context: Context) {
    private val appContext = context.applicationContext

    fun translate(
        text: String,
        sourceLanguage: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val intent = Intent("dev.davidv.translator.ITranslationService")
            .setPackage("dev.davidv.translator")

        val connection = object : ServiceConnection {
            private var completed = false

            private fun finish(action: () -> Unit) {
                if (completed) return
                completed = true
                try { appContext.unbindService(this) } catch (_: Exception) {}
                action()
            }

            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val api = ITranslationService.Stub.asInterface(binder)
                if (api == null) {
                    finish { onError("Não consegui iniciar o tradutor offline.") }
                    return
                }
                try {
                    api.translate(
                        text,
                        sourceLanguage,
                        "pt",
                        object : ITranslationCallback.Stub() {
                            override fun onTranslationResult(translatedText: String?) {
                                finish {
                                    val value = translatedText.orEmpty().trim()
                                    if (value.isBlank()) onError("O tradutor não retornou texto.")
                                    else onSuccess(value)
                                }
                            }

                            override fun onTranslationError(error: TranslationError?) {
                                finish {
                                    onError(error?.message?.takeIf { it.isNotBlank() }
                                        ?: "Não foi possível traduzir. Abra o Offline Translator e confira se o pacote de idiomas está baixado.")
                                }
                            }
                        }
                    )
                } catch (e: RemoteException) {
                    finish { onError("Falha na comunicação com o tradutor offline.") }
                } catch (e: Exception) {
                    finish { onError("Erro ao iniciar a tradução offline: ${e.message ?: "desconhecido"}") }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish { onError("O tradutor offline foi desconectado.") }
            }

            override fun onBindingDied(name: ComponentName?) {
                finish { onError("O serviço do tradutor offline foi encerrado.") }
            }
        }

        try {
            val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                onError("Instale e abra o Offline Translator para habilitar a tradução local. Depois, baixe o pacote de idiomas desejado.")
            }
        } catch (e: Exception) {
            onError("Não consegui conectar ao tradutor offline. Verifique se ele está instalado.")
        }
    }
}
