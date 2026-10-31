package com.rfwatch.app

import android.app.Application
import com.rfwatch.app.di.AppContainer

/** Application entry point; owns the single [AppContainer] (manual dependency graph). */
class RfWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
