package vin.lemon.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 本机页面经 {@code notify} 桥命令提交的通知内容。
 *
 * <p>只接受纯文本字段：标题必填，正文可空，长度受限，控制字符会被清理。
 * 解析失败一律返回 {@code null}，不把任意文本送进通知栏。
 */
public final class NotificationPayload {
    private static final String COMMAND = "notify";
    private static final int MAX_MESSAGE = 4096;
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 500;
    private static final int MAX_TASK_ID = 128;

    public final String title;
    public final String body;
    public final String taskId;
    /** 等待用户授权或回答的通知走高优先级渠道，其余走普通渠道。 */
    public final boolean urgent;

    private NotificationPayload(String title, String body, String taskId, boolean urgent) {
        this.title = title;
        this.body = body;
        this.taskId = taskId;
        this.urgent = urgent;
    }

    /** 同一任务重复通知替换上一条，而不是堆积一列。 */
    public int notificationId() {
        if (taskId.isEmpty()) return 1;
        return taskId.hashCode() & 0x7fffffff;
    }

    public static NotificationPayload parse(String message) {
        if (message == null || message.length() > MAX_MESSAGE) return null;
        if (!message.startsWith(COMMAND)) return null;
        String json = message.substring(COMMAND.length()).trim();
        if (json.isEmpty()) return null;
        try {
            JSONObject object = new JSONObject(json);
            String title = clean(object.optString("title", ""), MAX_TITLE);
            String body = clean(object.optString("body", ""), MAX_BODY);
            String taskId = clean(object.optString("taskId", ""), MAX_TASK_ID);
            if (title.isEmpty()) return null;
            String status = object.optString("status", "");
            return new NotificationPayload(title, body, taskId,
                    "permission_request".equals(status) || "elicitation_request".equals(status));
        } catch (JSONException exception) {
            return null;
        }
    }

    /** 去掉控制字符（保留换行供 BigTextStyle 使用），并截断到上限。 */
    private static String clean(String value, int max) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(Math.min(value.length(), max));
        for (int index = 0; index < value.length() && builder.length() < max; index++) {
            char current = value.charAt(index);
            if (current == '\n') {
                builder.append('\n');
            } else if (current == '\t') {
                builder.append(' ');
            } else if (!Character.isISOControl(current)) {
                builder.append(current);
            }
        }
        return builder.toString().trim();
    }
}
