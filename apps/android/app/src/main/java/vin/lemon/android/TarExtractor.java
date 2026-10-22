package vin.lemon.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 最小 tar 解包器：只支持 APK 自产数据包使用的 ustar/GNU 格式（普通文件、目录、
 * 符号链接、长文件名）。所有路径都经过 canonical 校验，拒绝绝对路径与 .. 逃逸。
 */
final class TarExtractor {
    private static final int BLOCK = 512;
    private static final int MAX_META = 64 * 1024;

    private TarExtractor() {}

    static void extract(InputStream input, File destination) throws IOException {
        byte[] header = new byte[BLOCK];
        String pendingName = null;
        String pendingLink = null;
        while (readBlock(input, header)) {
            if (isZero(header)) break;
            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            long size = readOctal(header, 124, 12);
            int type = header[156] & 0xff;
            String linkName = readString(header, 157, 100);
            if (!prefix.isEmpty() && !name.startsWith("/")) name = prefix + "/" + name;
            if (type == 'L' || type == 'K') {
                String value = readText(input, size);
                skipPadding(input, size);
                if (type == 'L') pendingName = value;
                else pendingLink = value;
                continue;
            }
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }
            if (pendingLink != null) {
                linkName = pendingLink;
                pendingLink = null;
            }
            handleEntry(input, destination, name, type, linkName, size);
            skipPadding(input, size);
        }
    }

    private static void handleEntry(
            InputStream input, File destination, String rawName, int type, String linkName, long size)
            throws IOException {
        String name = rawName.startsWith("./") ? rawName.substring(2) : rawName;
        if (name.isEmpty() || name.equals(".")) {
            skip(input, size);
            return;
        }
        File target = resolveInside(destination, name);
        if (type == '5') {
            skip(input, size);
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Cannot create runtime directory");
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create runtime directory");
        }
        if (type == '2') {
            skip(input, size);
            File linkTarget = resolveInside(destination, linkName);
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace runtime link");
            try {
                android.system.Os.symlink(linkTarget.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception exception) {
                throw new IOException("Cannot create runtime symlink", exception);
            }
            return;
        }
        if (type == 0 || type == '0' || type == '7') {
            try (FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output, size);
            }
            return;
        }
        // 其他类型（字符设备、归档扩展头等）不属于本包内容，直接跳过。
        skip(input, size);
    }

    private static File resolveInside(File destination, String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe archive path");
        }
        File base = destination.getCanonicalFile();
        File target = new File(base, name).getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("Archive path escapes destination");
        }
        return target;
    }

    private static String readText(InputStream input, long size) throws IOException {
        if (size > MAX_META) throw new IOException("Archive metadata entry too large");
        byte[] data = new byte[(int) size];
        readFully(input, data, data.length);
        return new String(data, StandardCharsets.UTF_8).split("\u0000", 2)[0];
    }

    private static void copy(InputStream input, FileOutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive entry");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(InputStream input, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive");
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        int remainder = (int) (size % BLOCK);
        if (remainder != 0) skip(input, BLOCK - remainder);
    }

    private static boolean readBlock(InputStream input, byte[] header) throws IOException {
        int offset = 0;
        while (offset < BLOCK) {
            int read = input.read(header, offset, BLOCK - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    private static void readFully(InputStream input, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) throw new IOException("Truncated archive metadata");
            offset += read;
        }
    }

    private static boolean isZero(byte[] header) {
        for (byte value : header) if (value != 0) return false;
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readOctal(byte[] header, int offset, int length) {
        long value = 0;
        for (int index = offset; index < offset + length; index++) {
            int digit = header[index] & 0xff;
            if (digit < '0' || digit > '7') continue;
            value = value * 8 + (digit - '0');
        }
        return value;
    }
}
