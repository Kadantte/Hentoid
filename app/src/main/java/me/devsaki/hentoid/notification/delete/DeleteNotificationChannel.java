package me.devsaki.hentoid.notification.delete;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Objects;

import me.devsaki.hentoid.R;

public class DeleteNotificationChannel {

    private DeleteNotificationChannel() {
        throw new IllegalStateException("Utility class");
    }

    static final String ID = "delete";

    // IMPORTANT : ALWAYS INIT THE CHANNEL BEFORE FIRING NOTIFICATIONS !
    public static void init(@NonNull final Context context) {
        String name = context.getString(R.string.notif_delete_title);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(ID, name, importance);
        channel.setSound(null, null);
        channel.setVibrationPattern(null);

        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        Objects.requireNonNull(notificationManager, "notificationManager must not be null");
        notificationManager.createNotificationChannel(channel);
    }
}
