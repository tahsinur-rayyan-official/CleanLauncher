package com.example.cleanlauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifService? = null
        @Volatile var listener: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        instance = this
        listener?.invoke()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        listener?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        listener?.invoke()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        listener?.invoke()
    }
}
