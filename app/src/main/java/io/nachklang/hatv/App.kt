package io.nachklang.hatv

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes

/** Hält den Anwendungskontext, damit auch Code ohne Context (Datenebene, Webserver) Texte aus den Ressourcen laden kann. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Res.ctx = this
    }
}

object Res {
    lateinit var ctx: Context

    fun s(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
}
