package org.liuym.flowerv1springboot.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 上传文件的真实类型判定与落盘安全（L05）。
 *
 * <p>三条不可让的判定：
 * <ol>
 *   <li><b>类型只认文件头字节</b>：Content-Type 与文件名后缀都是客户端自报的，改一个字节就能伪装；</li>
 *   <li><b>文件名服务端生成</b>：随机十六进制 + 白名单扩展名，既防路径穿越也防覆盖别人的文件；</li>
 *   <li><b>服务器绝对路径不出网</b>：接口与日志只回相对名，路径里常带用户名/部署目录结构。</li>
 * </ol>
 *
 * <p>不引 Tika 之类的魔数库（本批禁止加依赖），图片四类格式的签名足够短，手写判定即可全覆盖。
 */
public final class UploadPolicy {

    /** 落盘文件名形状：前缀 + 32 位十六进制 + 白名单扩展名 */
    private static final Pattern GENERATED_NAME = Pattern.compile("^[a-z]{2,6}_[0-9a-f]{32}\\.(jpg|png|gif|webp)$");

    /** 扩展名 → Content-Type，读取接口按这张表回响应头 */
    private static final java.util.Map<String, String> CONTENT_TYPES = java.util.Map.of(
            "jpg", "image/jpeg", "png", "image/png", "gif", "image/gif", "webp", "image/webp");

    private UploadPolicy() {
    }

    /**
     * 按文件头识别真实类型。
     *
     * <p>WEBP 的 RIFF 头前 4 字节与大量容器格式共用，必须再看 8~11 字节的 FOURCC，
     * 所以一次取 12 字节而不是逐格式猜长度。
     *
     * @return 白名单内的扩展名；识别不出（含脚本、pdf、zip 伪装的「图片」）返回 null
     */
    public static String detectExtension(byte[] head) {
        if (head == null || head.length < 12) {
            return null;
        }
        if (b(head, 0) == 0xFF && b(head, 1) == 0xD8 && b(head, 2) == 0xFF) {
            // SOI + 第一个标记段；只有 FFD8FF 三字节一致才认 JPEG，避免把随意拼出来的头当图片
            return "jpg";
        }
        if (b(head, 0) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return pngIfDimensionsOk(head);
        }
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return "gif";
        }
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * PNG 签名之后紧跟的必须是 IHDR 块（长度固定 13 字节）：
     * 只贴 8 字节签名再塞任意内容是最常见的伪装载荷，这里直接拒。
     */
    private static String pngIfDimensionsOk(byte[] head) {
        int declared = b(head, 8) << 24 | b(head, 9) << 16 | b(head, 10) << 8 | b(head, 11);
        return declared == 13 ? "png" : null;
    }

    /** 读取头部字节（不消耗调用方的流语义：只读前 n 字节，读不到返回短数组） */
    public static byte[] readHead(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int got = in.read(buf, read, n - read);
            if (got < 0) {
                break;
            }
            read += got;
        }
        if (read == n) {
            return buf;
        }
        byte[] shortBuf = new byte[read];
        System.arraycopy(buf, 0, shortBuf, 0, read);
        return shortBuf;
    }

    /** Content-Type 只在白名单里取，识别不出的一律 application/octet-stream，绝不回显客户端给的值 */
    public static String contentType(String extension) {
        return CONTENT_TYPES.getOrDefault(extension == null ? "" : extension.toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }

    public static boolean isSupported(String extension) {
        return extension != null && CONTENT_TYPES.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    /** 服务端生成落盘文件名：前缀 + 随机 32 位十六进制 + 真实类型后缀 */
    public static String generatedName(String prefix, String extension) {
        String clean = prefix == null || prefix.isEmpty() ? "up" : prefix.replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
        return (clean.length() > 6 ? clean.substring(0, 6) : clean)
                + "_" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    /** 文件名是否是我们自己发出去的那种形状（读取接口的第一道闸） */
    public static boolean isGeneratedName(String name) {
        return name != null && GENERATED_NAME.matcher(name).matches();
    }

    /**
     * 目标路径是否仍在存储目录内。
     *
     * <p>先 normalize 再比前缀：`..` 在 resolve 之后不会自己消失，
     * 只靠文件名正则挡不住大小写与编码变形，两道一起才算守住。
     */
    public static boolean within(Path baseDir, Path target) {
        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = target.toAbsolutePath().normalize();
        return resolved.startsWith(base);
    }

    /**
     * 大小上限的钳制：配置写错（0/负数/超过 20MB）时收敛到安全区间。
     * 下限留到 1KB 是刻意的——自检与测试要用小上限验证「超限即拒」，钳到 64KB 就没法测了。
     */
    public static long clampMaxBytes(long configured) {
        return Math.min(Math.max(configured, 1024), 20L * 1024 * 1024);
    }

    public static String describeLimit(long maxBytes) {
        return (maxBytes / 1024 / 1024) + "MB";
    }

    private static int b(byte[] buf, int index) {
        return buf[index] & 0xFF;
    }
}
