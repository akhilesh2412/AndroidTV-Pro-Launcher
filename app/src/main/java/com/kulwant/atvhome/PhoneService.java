package com.kulwant.atvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the phone control page running while other apps are on screen. A foreground service, so
 * Android doesn't kill the launcher process to free memory while you watch something.
 */
public class PhoneService extends Service {

    private PhoneServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("phone", "Phone control", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "phone")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Phone control is on")
                .build();
        startForeground(1, n);
        server = new PhoneServer(this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
