package com.clipboardsync.android.pairing

import android.content.Context
import android.os.Build
import com.clipboardsync.android.storage.CryptoUtils

class LocalDeviceIdentityStore(
    context: Context
) {
    private val prefs = context.getSharedPreferences("local_identity", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: CryptoUtils.uuidV7().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    val displayName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
