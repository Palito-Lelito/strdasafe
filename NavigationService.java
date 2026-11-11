package it.lucarisso.rotta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

/**
 * Rotta — servizio in primo piano attivo solo durante la navigazione (idea presa da StradaSafe).
 * Tiene vivo il processo e il GPS quando lo schermo è spento o c'è un'altra app davanti,
 * e passa ogni posizione a MainActivity, che la inoltra alla web app.
 * Tutta la logica di guida resta nella web app: qui ci sono solo la notifica e il GPS.
 */
public class NavigationService extends Service implements LocationListener {

    /** Chi riceve le posizioni (MainActivity). Chiamato sul thread principale. */
    public interface FixListener {
        void onFix(Location location);
    }

    public static final String EXTRA_DESTINATION = "destination";
    private static final String CHANNEL_ID = "rotta_nav";
    private static final int NOTIFICATION_ID = 1;

    private static volatile FixListener listener;

    static void setListener(FixListener l) { listener = l; }

    private LocationManager locationManager;
    private boolean listening = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String destination = intent != null ? intent.getStringExtra(EXTRA_DESTINATION) : null;
        try {
            Notification n = buildNotification(destination);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            // es. permesso di posizione revocato: senza primo piano il servizio non serve
            stopSelf();
            return START_NOT_STICKY;
        }
        startGps();
        // se il sistema chiude il processo la navigazione è persa: inutile ripartire da soli
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String destination) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Navigazione", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mostrata mentre Rotta ti sta guidando");
        nm.createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean named = destination != null && !destination.isEmpty();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Rotta · navigazione in corso")
                .setContentText(named ? "Verso " + destination : "Tocca per tornare alla mappa")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
    }

    private void startGps() {
        if (listening) return;
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            listening = true;
        } catch (SecurityException | IllegalArgumentException e) {
            // permesso mancante o dispositivo senza GPS: la web app continua con la posizione della WebView
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        FixListener l = listener;
        if (l != null && location != null) l.onFix(location);
    }

    // Prima di Android 11 questi metodi non hanno un'implementazione predefinita: vanno dichiarati
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    public void onDestroy() {
        if (listening && locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception e) { /* già rimosso */ }
        }
        listening = false;
        super.onDestroy();
    }
}
