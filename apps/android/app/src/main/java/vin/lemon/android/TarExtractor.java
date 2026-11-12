package vin.lemon.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/**
 * 最小 tar 解包器：只支持 APK 自产数据包使用的 ustar/GNU 格式（普通文件、目录、
 * 符号链接、长文件名）。所有路径都经过 canonical 校验，拒绝绝对路径与 .. 逃逸。
 */
final class TarExtractor {
    private static final int BLOCK = 512;
    private static final int MAX_META = 64 * 1024;
    private static final int IO_BUFFER_SIZE = 64 * 1024;

    private TarExtractor() {}

    static void extract(InputStream input, File destination) throws IOException {
        // 优化首次解包性能：在 extract 入口只解析一次根目录 canonical 路径，并复用单个 64KB 传输缓冲区，
        // 避免对 runtime-payload.tar 内数千个文件重复发起数千次 realpath 系统调用与数千次 64KB 数组分配。
        File canonicalBase = destination.getCanonicalFile();
        String basePrefix = canonicalBase.getPath() + File.separator;
        byte[] header = new byte[BLOCK];
        byte[] ioBuffer = new byte[IO_BUFFER_SIZE];
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
                skipPadding(input, size, ioBuffer);
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
            handleEntry(input, canonicalBase, basePrefix, name, type, linkName, size, ioBuffer);
            skipPadding(input, size, ioBuffer);
        }
    }

    private static void handleEntry(
            InputStream input, File canonicalBase, String basePrefix, String rawName,
            int type, String linkName, long size, byte[] ioBuffer)
            throws IOException {
        String name = rawName.startsWith("./") ? rawName.substring(2) : rawName;
        if (name.isEmpty() || name.equals(".")) {
            skip(input, size, ioBuffer);
            return;
        }
        File target = resolveInside(canonicalBase, basePrefix, name);
        if (type == '5') {
            skip(input, size, ioBuffer);
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
            skip(input, size, ioBuffer);
            File linkTarget = resolveInside(canonicalBase, basePrefix, linkName);
            // 修复断链覆盖失败：File.exists() 会跟随符号链接，若旧符号链接是悬空断链（目标尚未解包），
            // exists() 返回 false 不删除，随后创建新链接会抛 EEXIST。改用 NOFOLLOW_LINKS 判定并删除。
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && !Files.deleteIfExists(target.toPath())) {
                throw new IOException("Cannot replace runtime link");
            }
            createSymbolicLink(linkTarget, target);
            return;
        }
        if (type == 0 || type == '0' || type == '7') {
            try (FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output, size, ioBuffer);
            }
            return;
        }
        // 其他类型（字符设备、归档扩展头等）不属于本包内容，直接跳过。
        skip(input, size, ioBuffer);
    }

    private static void createSymbolicLink(File linkTarget, File linkFile) throws IOException {
        try {
            Files.createSymbolicLink(linkFile.toPath(), linkTarget.toPath());
        } catch (UnsupportedOperationException | SecurityException fallback) {
            try {
                android.system.Os.symlink(linkTarget.getAbsolutePath(), linkFile.getAbsolutePath());
            } catch (Exception exception) {
                throw new IOException("Cannot create runtime symlink", exception);
            }
        }
    }

    static File resolveInside(File canonicalBase, String basePrefix, String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe archive path");
        }
        File target = new File(canonicalBase, name).getCanonicalFile();
        if (!target.equals(canonicalBase) && !target.getPath().startsWith(basePrefix)) {
            throw new IOException("Archive path escapes destination");
        }
        return target;
    }

    private static String readText(InputStream input, long size) throws IOException {
        if (size < 0 || size > MAX_META) throw new IOException("Archive metadata entry too large");
        byte[] data = new byte[(int) size];
        readFully(input, data, data.length);
        return new String(data, StandardCharsets.UTF_8).split("\u0000", 2)[0];
    }

    private static void copy(InputStream input, FileOutputStream output, long size, byte[] buffer)
            throws IOException {
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive entry");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(InputStream input, long size, byte[] buffer) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive");
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream input, long size, byte[] buffer) throws IOException {
        int remainder = (int) (size % BLOCK);
        if (remainder != 0) skip(input, BLOCK - remainder, buffer);
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
