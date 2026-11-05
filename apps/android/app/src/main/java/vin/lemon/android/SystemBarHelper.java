package vin.lemon.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 原生系统栏（状态栏与导航栏）、边距 Insets 以及机身圆角适配助手。
 *
 * <p>将系统栏沉浸式、亮暗色模式跟随以及屏幕真实圆角裁剪等纯 UI 窗口逻辑
 * 从 {@link MainActivity} 中抽离，降低主 Activity 复杂度。
 */
final class SystemBarHelper {
    /** iOS 浅色分组背景（#F2F2F7），与 Web 侧浅色主题基底一致。 */
    static final int UI_BACKGROUND_LIGHT = 0xFFF2F2F7;
    /** iOS 深色背景（纯黑），与 .theme-zai-dark 的 --color-background 一致。 */
    static final int UI_BACKGROUND_DARK = 0xFF000000;

    private final Activity activity;
    private Boolean appThemeIsLight;

    SystemBarHelper(Activity activity) {
        this.activity = activity;
    }

    /** 配置全屏 Edge-to-Edge 边距监听与系统对比度防护。 */
    void configureInsets(View root, View webContainer) {
        if (root == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        // 关掉系统给透明系统栏自动加的对比度蒙层。开着时系统会在状态栏/导航栏
        // 与内容之间画一条半透明带子，皮肤上就是“割裂”。
        if (Build.VERSION.SDK_INT >= 29) {
            activity.getWindow().setStatusBarContrastEnforced(false);
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }

        applyRoundedCorners(root);
        if (webContainer != null) applyRoundedCorners(webContainer);
        applySystemBarAppearance();
        root.requestApplyInsets();
    }

    /**
     * 把原生外壳（状态栏区域底色 + 系统图标明暗）对齐到**页面主题**。
     *
     * <p>此前只看系统 uiMode：App 内选浅色而系统是深色时，状态栏区域仍是黑底，
     * 图标又是浅色，于是看起来「状态栏消失」。页面每次应用主题都会发 {@code theme}，
     * 原生据此切换；未收到页面主题前退回系统偏好。
     */
    void applyPageTheme(boolean light, View root, View panel) {
        appThemeIsLight = light;
        int background = light ? UI_BACKGROUND_LIGHT : UI_BACKGROUND_DARK;
        if (root != null) root.setBackgroundColor(background);
        if (panel != null) panel.setBackgroundColor(background);
        applySystemBarAppearance();
    }

    /** 系统栏图标明暗：优先页面主题；未收到上报时按应用默认（深色）。 */
    void applySystemBarAppearance() {
        Boolean configured = appThemeIsLight;
        // 未收到页面上报时按应用默认深色处理，不再退回系统 uiMode。
        // 应用默认主题是 zai-dark，页面挂载后会用 theme 命令上报真实主题。
        boolean light = configured != null ? configured : false;
        int background = light ? UI_BACKGROUND_LIGHT : UI_BACKGROUND_DARK;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                // 浅色界面要深色图标（LIGHT_*），深色界面要浅色图标。
                controller.setSystemBarsAppearance(light ? appearance : 0, appearance);
            }
            activity.getWindow().setStatusBarColor(background);
            activity.getWindow().setNavigationBarColor(background);
        } else if (Build.VERSION.SDK_INT >= 27) {
            activity.getWindow().getDecorView().setSystemUiVisibility(light
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            activity.getWindow().setStatusBarColor(background);
            activity.getWindow().setNavigationBarColor(background);
        } else {
            // Android 8.0 无深色导航图标；浅色背景会让系统按钮不可见。
            activity.getWindow().setNavigationBarColor(Color.BLACK);
        }
    }

    /**
     * 让界面边界跟随机身圆角。
     *
     * <p>按设备实际圆角半径把视图裁成圆角。半径读系统资源；取不到或为 0（真直屏/平板）就保持原样。
     */
    void applyRoundedCorners(View view) {
        final int radius = resolveDeviceCornerRadius();
        if (radius <= 0 || view == null) return;
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radius);
            }
        });
    }

    /** 读取设备真实圆角半径。Android 12 起可用公开 API。 */
    private int resolveDeviceCornerRadius() {
        if (Build.VERSION.SDK_INT < 31) return 0;
        try {
            WindowInsets insets = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            android.view.RoundedCorner topLeft =
                    insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
            if (topLeft != null && topLeft.getRadius() > 0) return topLeft.getRadius();
            android.view.RoundedCorner topRight =
                    insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT);
            if (topRight != null && topRight.getRadius() > 0) return topRight.getRadius();
        } catch (RuntimeException ignored) {
            // 部分 ROM 不实现该 API：不裁剪即可，不影响其它布局。
        }
        return 0;
    }
}
