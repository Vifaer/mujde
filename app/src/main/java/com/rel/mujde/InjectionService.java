package com.rel.mujde;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/** Keeps Mujde process warm so inject broadcasts are delivered. */
public class InjectionService extends Service {
    public static void start(Context context) {
        Intent i = new Intent(context, InjectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        Intent open = new Intent(this, ActivityMain.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Mujde")
                .setContentText("注入监听已运行 · Frida " + BuildConfig.FRIDA_VERSION)
                .setSmallIcon(R.drawable.ic_script)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(Constants.NOTIFICATION_ID_SERVICE, notification);
        LogStore.append(this, "InjectionService started (frida=" + BuildConfig.FRIDA_VERSION + ")");
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "Mujde Injection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Mujde ready for frida-inject requests");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
