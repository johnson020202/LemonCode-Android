package vin.lemon.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * 任务完成、失败与待确认的系统通知。
 *
 * <p>只使用公开 Android 通知能力（通知渠道 + NotificationCompat），不依赖任何
 * 厂商私有接口；是否提醒由原生壳按前后台判断，页面只负责描述内容。
 *
 * <p>任务进行中使用 Android 16 的标准 Live Updates（promoted ongoing notification），
 * 不接小米超级岛的私有 extras——后者需要小米开发者平台注册与审核，第三方无法自主接入。
 */
final class TaskNotifications {
    static final String CHANNEL_UPDATES = "task-updates";
    static final String CHANNEL_ACTIONS = "task-actions";
    static final String CHANNEL_PROGRESS = "task-progress";

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
        // promoted 通知要求渠道 importance 不为 MIN；LOW 足够安静，也不会被系统拒绝提升。
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                context.getString(R.string.notification_channel_progress),
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(
                context.getString(R.string.notification_channel_progress_description));
        progress.setShowBadge(false);
        manager.createNotificationChannel(updates);
        manager.createNotificationChannel(actions);
        manager.createNotificationChannel(progress);
    }

    /** 清掉上一次进程遗留的进行中通知；进程被杀后常驻通知不会自行消失。 */
    static void clearStaleProgress(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (CHANNEL_PROGRESS.equals(active.getNotification().getChannelId())) {
                manager.cancel(active.getId());
            }
        }
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
        if (payload.isDismiss()) {
            manager.cancel(payload.notificationId());
            return;
        }
        if (payload.isRunning()) {
            postProgress(context, manager, payload);
            return;
        }
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

    /**
     * 进行中通知：常驻 + 状态栏计时器。
     *
     * <p>满足 Android 16 promoted 条件：常驻、有 contentTitle、BigTextStyle、
     * 无自定义 RemoteView、非分组摘要、未 colorized、渠道非 MIN。API 36 的
     * `setRequestPromotedOngoing` 用反射调用，低版本静默退化为普通常驻通知。
     */
    private static void postProgress(Context context, NotificationManagerCompat manager,
                                     NotificationPayload payload) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long startedAt = payload.startedAt > 0 ? payload.startedAt : System.currentTimeMillis();
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setStyle(new Notification.BigTextStyle().bigText(payload.body))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(startedAt)
                .setUsesChronometer(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(content);
        requestPromotion(builder);
        try {
            manager.notify(payload.notificationId(), builder.build());
        } catch (SecurityException ignored) {
            // 同上：权限撤销后放弃，不影响任务本身。
        }
    }

    /** API 36 的 promoted ongoing 请求；低版本没有该方法时保持普通常驻通知。 */
    private static void requestPromotion(Notification.Builder builder) {
        if (Build.VERSION.SDK_INT < 36) return;
        try {
            Notification.Builder.class
                    .getMethod("setRequestPromotedOngoing", boolean.class)
                    .invoke(builder, true);
        } catch (ReflectiveOperationException ignored) {
            // 平台未提供该 API：降级即可，不抛错。
        }
    }
}
