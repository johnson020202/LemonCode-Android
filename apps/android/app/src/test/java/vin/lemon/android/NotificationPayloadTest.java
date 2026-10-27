package vin.lemon.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NotificationPayloadTest {
    private static final UrlPolicy.LocalOrigin LOCAL = new UrlPolicy.LocalOrigin(31234);

    @Test public void parsesTaskCompletionWithBodyAndId() {
        NotificationPayload payload = NotificationPayload.parse(
                "notify {\"taskId\":\"sess_1\",\"status\":\"completed\","
                        + "\"title\":\"任务完成\",\"body\":\"已生成 3 个文件\"}");
        assertNotNull(payload);
        assertEquals("任务完成", payload.title);
        assertEquals("已生成 3 个文件", payload.body);
        assertEquals("sess_1", payload.taskId);
        assertFalse(payload.urgent);
    }

    @Test public void permissionRequestsUseTheUrgentChannel() {
        NotificationPayload payload = NotificationPayload.parse(
                "notify {\"taskId\":\"sess_2\",\"status\":\"permission_request\","
                        + "\"title\":\"需要授权\",\"body\":\"允许执行命令\"}");
        assertNotNull(payload);
        assertTrue(payload.urgent);
    }

    @Test public void sameTaskReplacesItsPreviousNotification() {
        NotificationPayload first = NotificationPayload.parse(
                "notify {\"taskId\":\"sess_1\",\"title\":\"a\",\"body\":\"\"}");
        NotificationPayload second = NotificationPayload.parse(
                "notify {\"taskId\":\"sess_1\",\"status\":\"failed\",\"title\":\"b\",\"body\":\"\"}");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.notificationId(), second.notificationId());
    }

    @Test public void rejectsMalformedPayloads() {
        assertNull(NotificationPayload.parse(null));
        assertNull(NotificationPayload.parse("scan"));
        assertNull(NotificationPayload.parse("notify"));
        assertNull(NotificationPayload.parse("notify {"));
        assertNull(NotificationPayload.parse("notify {\"body\":\"没有标题\"}"));
        assertNull(NotificationPayload.parse("notify {\"title\":\"   \"}"));
        assertNull(NotificationPayload.parse("notify " + "x".repeat(5000)));
    }

    @Test public void stripsControlCharactersAndTrimsLength() {
        NotificationPayload payload = NotificationPayload.parse(
                "notify {\"title\":\"a\\u0000b\",\"body\":\""
                        + "y".repeat(900) + "\"}");
        assertNotNull(payload);
        assertEquals("ab", payload.title);
        assertEquals(500, payload.body.length());
    }

    @Test public void bridgeAcceptsNotifyAndKeepsOtherCommandsExact() {
        assertTrue(LocalBridgePolicy.isSupportedCommand("notify"));
        assertTrue(LocalBridgePolicy.isSupportedCommand("scan"));
        assertTrue(LocalBridgePolicy.isSupportedCommand("url"));
        assertFalse(LocalBridgePolicy.isSupportedCommand("notifyAll"));
        assertFalse(LocalBridgePolicy.isSupportedCommand(null));
        // notify 命令本身仍要通过来源校验，不能借带参数绕过。
        assertTrue(LocalBridgePolicy.allows(LOCAL, "notify",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234", true));
        assertFalse(LocalBridgePolicy.allows(LOCAL, "notify",
                "http://127.0.0.1:31234/", "http://127.0.0.1:31234", false));
    }
}
