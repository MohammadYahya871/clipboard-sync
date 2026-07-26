package com.clipboardsync.android.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class ClipboardObserver(
    context: Context,
    private val onClipboardChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        mainHandler.post { onClipboardChanged() }
    }
    private var started = false

    fun start() {
        if (started) return
        clipboardManager.addPrimaryClipChangedListener(listener)
        started = true
    }

    fun stop() {
        if (!started) return
        clipboardManager.removePrimaryClipChangedListener(listener)
        started = false
    }
}
