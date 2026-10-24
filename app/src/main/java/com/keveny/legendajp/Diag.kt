package com.keveny.legendajp

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.speech.RecognitionService

/** Informações sobre quais serviços de reconhecimento o aparelho usa. */
object Diag {

    /** Serviço on-device configurado pelo fabricante (lido do framework; pode ser vazio). */
    fun onDeviceService(): String {
        return try {
            val res = Resources.getSystem()
            val id = res.getIdentifier("config_defaultOnDeviceSpeechRecognitionService", "string", "android")
            if (id != 0) res.getString(id) else "(não configurado)"
        } catch (e: Exception) {
            "(erro: ${e.message})"
        }
    }

    /** Serviço de voz padrão (usado por createSpeechRecognizer). */
    fun defaultService(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") ?: "(não configurado)"

    /** Todos os serviços de reconhecimento instalados. */
    fun installedServices(ctx: Context): List<String> =
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName + "/" + it.serviceInfo.name }

    fun summary(ctx: Context): String =
        "on-device: ${onDeviceService()}\npadrão: ${defaultService(ctx)}\ninstalados: ${installedServices(ctx).joinToString(", ")}"
}
