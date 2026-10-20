package com.peaknav.compatibility;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.peaknav.R;

public class NotificationManagerAndroid extends NotificationManagerPeakNav {

    private final NotificationManagerCompat notificationManager;
    private String CHANNEL_ID = "peakNavNotChannelId";
    private static final int PROGRESS_MAX = 100;
    private static final int NOTIFICATION_ID = 7817;
    private final Context context;

    public NotificationManagerAndroid(Context context) {
        this.context = context;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager1;
            CharSequence name = context.getString(R.string.mv_app_name);
            String description = context.getString(R.string.mv_app_name);
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            notificationManager1 = context.getSystemService(NotificationManager.class);
            notificationManager1.createNotificationChannel(channel);
        }

        notificationManager = NotificationManagerCompat.from(context);
    }

    @Override
    public void setText(String text, float progress) {

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.peaknav.R.drawable.mv_mountain_icon)
                .setContentTitle(context.getString(R.string.mv_app_name))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        int progressCurrent = Math.round(progress*PROGRESS_MAX);
        builder.setProgress(PROGRESS_MAX, progressCurrent, false);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    @Override
    public void clear() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
