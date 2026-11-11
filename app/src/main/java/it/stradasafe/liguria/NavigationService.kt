package it.stradasafe.liguria

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servizio in primo piano che tiene vivo il processo (GPS + voce) durante la navigazione.
 * La logica di guida vive in MainActivity: qui c'è solo la notifica persistente.
 */
class NavigationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // minSdk 26: il canale di notifica è sempre richiesto
        val channel = NotificationChannel(CHANNEL_ID, "Navigazione attiva", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Navigazione in corso"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StradaSafe 1.1")
            .setContentText("Navigazione in background attiva")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Es. permesso posizione revocato: senza foreground il servizio non ha motivo di restare attivo
            stopSelf()
            return START_NOT_STICKY
        }
        // Se il sistema uccide il processo la navigazione è persa: inutile far ripartire il servizio da solo
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "STOP"
        private const val CHANNEL_ID = "stradasafe_nav"
        private const val NOTIFICATION_ID = 1
    }
}
