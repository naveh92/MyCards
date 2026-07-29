package com.mycards.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.mycards.R;
import com.mycards.ui.reconcile.ReconcileActivity;

/** Notification channel setup and the one alert this app raises. */
public final class Notifications {

    private Notifications() {
    }

    public static final String CHANNEL_MISMATCH = "balance_mismatch";

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_MISMATCH) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_MISMATCH,
                context.getString(R.string.unlogged_transaction_title),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reconcile_explain));
        manager.createNotificationChannel(channel);
    }

    /**
     * Tells the user a card holds less than the spend log accounts for, and opens the
     * reconcile screen with the difference pre-filled.
     */
    public static void showBalanceMismatch(Context context, long cardId, String cardLabel) {
        ensureChannels(context);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            // Permission not granted; the mismatch flag on the card still surfaces in-app.
            return;
        }

        Intent intent = new Intent(context, ReconcileActivity.class);
        intent.putExtra(ReconcileActivity.EXTRA_CARD_ID, cardId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pending = PendingIntent.getActivity(
                context,
                (int) cardId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_MISMATCH)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.unlogged_transaction_title))
                .setContentText(context.getString(R.string.unlogged_transaction_body, cardLabel))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.unlogged_transaction_body, cardLabel)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        try {
            NotificationManagerCompat.from(context).notify((int) cardId, notification);
        } catch (SecurityException denied) {
            // Notifications revoked between the check and the post; the in-app flag remains.
        }
    }
}
