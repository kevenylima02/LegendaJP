package com.keveny.legendajp

import android.app.Application

class MichiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Carrega o dicionário do Kuromoji e as palavras salvas sem travar a abertura do app
        Thread({
            try {
                VocabDb.get(this).reloadKeys()
                Jp.warmUp()
            } catch (e: Exception) {
                android.util.Log.e(CaptureService.TAG, "warmUp", e)
            }
        }, "michi-warmup").start()
    }
}
