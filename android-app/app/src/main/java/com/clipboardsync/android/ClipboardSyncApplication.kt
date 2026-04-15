package com.clipboardsync.android

import android.app.Application
import com.clipboardsync.android.service.AppContainer

class ClipboardSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

