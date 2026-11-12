package vin.lemon.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

/**
 * 远程控制（镜像）会话的前台保活服务。
 *
 * <p>页面退到后台后，系统会把 WebView 的 JS 定时器节流到每分钟一次甚至暂停，而远控心跳
 * 要求每 30s 一次、Worker 连丢两个周期（约 75s）就关桥。这里把进程提到前台优先级，
 * 并由原生 Handler 每 20s 直接调用页面的心跳钩子
 * （{@code window.__lcodeRemotePingTick}），不依赖会被节流的 JS 定时器。
 *
 * <p>只在镜像页面打开期间运行；回到本机工作区或退出应用时立即停止，避免常驻耗电。
 */
public final class MirrorKeepAliveService extends Service {
    private static final String CHANNEL_ID = "remote-keepalive";
    private static final int NOTIFICATION_ID = 41721;
    /** 比 JS 侧 30s 心跳更密：即使系统吞掉一两拍，仍落在 Worker 的 75s 关闭窗口内。 */
    private static final long TICK_INTERVAL_MS = 20_000L;
    /** 单次持锁时间上限（4 小时），在活跃心跳期间自动续期，防止异常场景永久锁死 CPU 耗电。 */
    private static final long WAKELOCK_LEASE_MS = 4L * 60L * 60L * 1000L;
    /** 连续丢失目标 WebView 的最大容忍拍数（约 2 分钟），超限自动停止空转保活服务。 */
    private static final int MAX_IDLE_TICKS = 6;
    private static final String PING_SCRIPT =
            "window.__lcodeRemotePingTick&&window.__lcodeRemotePingTick()";

    /** 当前镜像页的 WebView；弱引用避免服务拖住已销毁的页面。 */
    private static volatile WeakReference<WebView> target;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean ticking;
    private int idleTicks;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            WeakReference<WebView> ref = target;
            WebView view = ref == null ? null : ref.get();
            if (view != null) {
                idleTicks = 0;
                ensureWakeLockHeld();
                try {
                    view.evaluateJavascript(PING_SCRIPT, null);
                } catch (RuntimeException ignored) {
                    // 页面或渲染进程已销毁：下一拍等重建后自然恢复，不在这里重建 WebView。
                }
            } else if (++idleTicks >= MAX_IDLE_TICKS) {
                // 目标页面已长时间不存在（例如进程重建后未再进入镜像页），主动退出避免后台白挂通知与耗电。
                stopSelf();
                return;
            }
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    /** 由 MainActivity 在打开镜像页后调用；页面销毁时清空。 */
    static void setTarget(WebView webView) {
        target = webView == null ? null : new WeakReference<>(webView);
    }

    static void clearTarget() {
        target = null;
    }

    static void start(Context context) {
        ContextCompat.startForegroundService(context,
                new Intent(context, MirrorKeepAliveService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, MirrorKeepAliveService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            // 按包名隔离 WakeLock 标签，方便原版与共存版在系统电池统计中各自独立归因。
            wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":mirror-keepalive");
            wakeLock.setReferenceCounted(false);
            ensureWakeLockHeld();
        }
    }

    private void ensureWakeLockHeld() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(WAKELOCK_LEASE_MS);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        idleTicks = 0;
        ensureWakeLockHeld();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // connectedDevice：与已配对电脑维持网络连接，属于该类型；dataSync 在 Android 15
            // 有 6 小时/24 小时上限，无法满足长时间远控。
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (!ticking) {
            ticking = true;
            handler.postDelayed(tick, TICK_INTERVAL_MS);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        ticking = false;
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keepalive_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(content)
                .build();
    }
}
