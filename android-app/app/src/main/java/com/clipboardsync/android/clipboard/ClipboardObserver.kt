package com.clipboardsync.android.clipboard

import android.content.ClipboardManager
import android.content.Context

class ClipboardObserver(
    context: Context,
    private val onClipboardChanged: () -> Unit
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
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

