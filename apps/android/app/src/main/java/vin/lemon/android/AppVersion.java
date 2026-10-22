package vin.lemon.android;

/**
 * 版本比较：只处理发布 tag 里的语义化版本（android-v0.1.2 / 0.1.2）。
 * 不用字符串比较，避免 0.1.10 被判定为小于 0.1.9。
 */
public final class AppVersion {
    private AppVersion() {}

    /** 从 "android-v0.1.2"、"v0.1.2"、"0.1.2" 提取数字段；无法解析返回 null。 */
    public static int[] parse(String tag) {
        if (tag == null) return null;
        String value = tag.trim();
        if (value.startsWith("android-")) value = value.substring("android-".length());
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        // 允许后缀（-beta 等）；只取前面的数字段。
        int end = 0;
        while (end < value.length()
                && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) return null;
        String core = value.substring(0, end);
        String[] parts = core.split("\\.", -1);
        if (parts.length == 0 || parts.length > 4) return null;
        int[] numbers = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 9) return null;
            for (int digit = 0; digit < part.length(); digit++) {
                if (!Character.isDigit(part.charAt(digit))) return null;
            }
            numbers[index] = Integer.parseInt(part);
        }
        return numbers;
    }

    /** newer 是否严格新于 current；任一不可解析时返回 false（保守不提示升级）。 */
    public static boolean isNewer(String newer, String current) {
        int[] left = parse(newer);
        int[] right = parse(current);
        if (left == null || right == null) return false;
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int a = index < left.length ? left[index] : 0;
            int b = index < right.length ? right[index] : 0;
            if (a != b) return a > b;
        }
        return false;
    }
}
