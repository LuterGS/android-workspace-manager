package dev.lutergs.android_wm.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import dev.lutergs.android_wm.IWindowTilingService

class ShizukuServiceConnection : ServiceConnection {
    var service: IWindowTilingService? = null
        private set

    val isConnected: Boolean
        get() = service != null

    /**
     * Notified whenever [service] actually changes. bindUserService() itself returns
     * before the bind completes, so anyone showing "connected" UI needs this rather
     * than assuming the call succeeded the moment it was made (see MainActivity).
     */
    var onConnectionChanged: (() -> Unit)? = null

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = IWindowTilingService.Stub.asInterface(binder)
        onConnectionChanged?.invoke()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
        onConnectionChanged?.invoke()
    }
}
