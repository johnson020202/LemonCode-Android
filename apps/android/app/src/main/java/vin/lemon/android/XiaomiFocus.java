package vin.lemon.android;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 小米焦点通知 / 超级岛（miui.focus.param）。
 *
 * <p>HyperOS 有两条独立的通道，互不冲突：
 * <ul>
 *   <li>通道一 AOSP promoted ongoing（实时动态）。系统支持就会进岛，岛上取 smallIcon +
 *       shortCriticalText。无需任何小米授权，本应用主路径走这条。</li>
 *   <li>通道二 miui.focus.param。需要系统焦点协议 ≥1 且本应用被授予焦点通知权限，
 *       才能自定义大岛/小岛/息屏文案。授权由小米审核发放，侧载包拿不到；</li>
 * </ul>
 * 因此这里的模板只在 {@link #allowed} 为真时才附加，未授权时什么都不做，
 * 既不会污染普通通知，也不影响通道一。
 *
 * <p>参数格式与字节上限取自小米官方《超级岛开发指南》与社区真机验证实现
 * （HyperOS 4.0 / Android 17）。
 */
final class XiaomiFocus {
    static final String EXTRA_PARAM = "miui.focus.param";
    static final String EXTRA_PICS = "miui.focus.pics";
    private static final String PIC_ICON = "miui.focus.pic_icon";

    private static final String PROTOCOL_SETTING = "notification_focus_protocol";
    private static final Uri FOCUS_PROVIDER =
            Uri.parse("content://miui.statusbar.notification.public");

    /** 模板硬上限：超过后 SystemUI 整条丢弃。 */
    private static final int PARAM_MAX_BYTES = 3072;
    /** 岛上文字区域只有约四个字宽。 */
    private static final int ISLAND_CHARS = 6;
    /** canShowFocus 要跨进程调用 SystemUI，缓存一分钟。 */
    private static final long CHECK_TTL_MS = 60_000L;

    private static long checkedAt = 0L;
    private static boolean cached = false;

    private XiaomiFocus() {}

    /** 是否小米机型。 */
    static boolean isXiaomi() {
        String os = prop("ro.mi.os.version.name", "");
        String miui = prop("ro.miui.ui.version.name", "");
        return !os.isEmpty() || !miui.isEmpty();
    }

    /** 系统是否声明支持超级岛。 */
    static boolean hasIsland() {
        String value = prop("persist.sys.feature.island", "0");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /** 焦点通知协议：0 不支持，1 OS1，2 OS2，3 及以上为超级岛。 */
    static int focusProtocol(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(), PROTOCOL_SETTING, 0);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /**
     * 本应用是否被系统授予焦点通知权限。
     *
     * <p>由 SystemUI 决定（小米审核后按包名放行）；调用可能抛 SecurityException，
     * 一律当作未授权，不影响标准实时动态路径。
     */
    static boolean canShowFocus(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt < CHECK_TTL_MS) return cached;
        checkedAt = now;
        try {
            Bundle extras = new Bundle();
            extras.putString("package", context.getPackageName());
            Bundle result = context.getContentResolver()
                    .call(FOCUS_PROVIDER, "canShowFocus", null, extras);
            cached = result != null && result.getBoolean("canShowFocus", false);
        } catch (RuntimeException exception) {
            cached = false;
        }
        return cached;
    }

    /** 通道二是否可用：机型支持岛 + 协议达标 + 应用已被放行。 */
    static boolean allowed(Context context) {
        return isXiaomi() && hasIsland() && focusProtocol(context) >= 1
                && canShowFocus(context);
    }

    /** 一句话诊断，供首页与日志排查。 */
    static String describe(Context context) {
        if (!isXiaomi()) return "";
        return "岛" + (hasIsland() ? "支持" : "不支持")
                + " · 协议" + focusProtocol(context)
                + " · 焦点通知" + (canShowFocus(context) ? "已开" : "未开");
    }

    /**
     * 给进行中通知附加焦点模板。未授权时直接返回，不改变通知内容。
     *
     * @param title 通知标题（岛展开态与通知卡正文）
     * @param text  当前进度正文
     * @param chip  岛上显示的短词（约 4 字）
     */
    static void apply(Context context, android.app.Notification.Builder builder,
                      String title, String text, String chip) {
        if (!allowed(context)) return;
        try {
            Bundle extras = new Bundle();
            extras.putString(EXTRA_PARAM, param(context, title, text, chip));
            extras.putBundle(EXTRA_PICS, Bundle.EMPTY);
            builder.addExtras(extras);
        } catch (RuntimeException ignored) {
            // 模板异常不能影响标准实时动态通知本身。
        }
    }

    /** 构建 param_v2 JSON；超长时逐步截断正文，保证不越过字节上限。 */
    private static String param(Context context, String title, String text, String chip) {
        String word = shorten(chip == null || chip.isEmpty() ? title : chip, ISLAND_CHARS);
        String body = text == null ? "" : text;
        String head = title == null ? "" : title;
        while (true) {
            String json = template(word, head, body);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    <= PARAM_MAX_BYTES) {
                return json;
            }
            if (!body.isEmpty()) {
                body = body.substring(0, body.length() / 2);
            } else if (head.length() > 8) {
                head = head.substring(0, head.length() / 2);
            } else {
                return json;
            }
        }
    }

    private static String template(String word, String title, String text) {
        try {
            JSONObject picInfo = new JSONObject().put("type", 1).put("pic", PIC_ICON);

            JSONObject bigIsland = new JSONObject()
                    .put("templateNo", 2)
                    .put("imageTextInfoLeft", new JSONObject()
                            .put("type", 1)
                            .put("picInfo", picInfo)
                            .put("textInfo", new JSONObject().put("title", word)))
                    .put("textInfo", new JSONObject()
                            .put("title", word)
                            .put("content", "")
                            .put("showHighlightColor", false)
                            .put("narrowFont", false));

            JSONObject paramIsland = new JSONObject()
                    .put("islandProperty", 1)
                    .put("bigIslandArea", bigIsland)
                    .put("smallIslandArea", new JSONObject().put("picInfo", picInfo));

            JSONObject paramV2 = new JSONObject()
                    .put("protocol", 1)
                    .put("business", "lcode_task")
                    .put("updatable", true)
                    .put("enableFloat", false)
                    .put("ticker", word + " · " + title)
                    .put("aodTitle", word)
                    .put("param_island", paramIsland)
                    .put("baseInfo", new JSONObject()
                            .put("type", 2)
                            .put("title", title)
                            .put("content", text));

            return new JSONObject().put("param_v2", paramV2).toString();
        } catch (JSONException exception) {
            return "{}";
        }
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** android.os.SystemProperties 是隐藏类，只能反射读取。 */
    private static String prop(String key, String fallback) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
            return value == null ? fallback : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return fallback;
        }
    }

    /** 供界面展示的岛名称。 */
    static String islandName(Context context) {
        if (isXiaomi() && hasIsland()) return "超级岛";
        return "实时动态";
    }
}
