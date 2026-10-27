package vin.lemon.android;

import android.Manifest;
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

/**
 * 任务完成、失败与待确认的系统通知。
 *
 * <p>只使用公开 Android 通知能力（通知渠道 + NotificationCompat），不依赖任何
 * 厂商私有接口；是否提醒由原生壳按前后台判断，页面只负责描述内容。
 */
final class TaskNotifications {
    static final String CHANNEL_UPDATES = "task-updates";
    static final String CHANNEL_ACTIONS = "task-actions";

    private TaskNotifications() {}

    /** 通知渠道必须在首次发送前建好；重复创建同名渠道是幂等的。 */
    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel updates = new NotificationChannel(CHANNEL_UPDATES,
                context.getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_DEFAULT);
        updates.setDescription(context.getString(R.string.notification_channel_tasks_description));
        NotificationChannel actions = new NotificationChannel(CHANNEL_ACTIONS,
                context.getString(R.string.notification_channel_actions),
                NotificationManager.IMPORTANCE_HIGH);
        actions.setDescription(context.getString(R.string.notification_channel_actions_description));
        manager.createNotificationChannel(updates);
        manager.createNotificationChannel(actions);
    }

    static void post(Context context, NotificationPayload payload) {
        if (payload == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return; // 用户未授权：静默放弃，不影响任务本身。
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) return;
        ensureChannels(context);
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                payload.urgent ? CHANNEL_ACTIONS : CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(payload.body))
                .setPriority(payload.urgent
                        ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(content);
        try {
            manager.notify(payload.notificationId(), builder.build());
        } catch (SecurityException ignored) {
            // 权限可能在检查与发送之间被撤销；此时放弃提醒即可。
        }
    }
}
