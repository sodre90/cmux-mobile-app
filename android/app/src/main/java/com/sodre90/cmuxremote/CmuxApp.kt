package com.sodre90.cmuxremote

import android.app.Application
import com.sodre90.cmuxremote.data.AppContainer

class CmuxApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor for the app-wide container from any Context. */
val Application.container: AppContainer
    get() = (this as CmuxApp).container
