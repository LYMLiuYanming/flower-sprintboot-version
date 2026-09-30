package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.ErrorCode;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.UploadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * F01 评价图片上传与读取（L05 加固：判定表、路径闸与出网字段收进 {@link UploadPolicy}）。
 *
 * <p>落盘位置默认在运行目录下的 {@code upload/review}（可用 app.review-upload.dir 指向独立数据卷），
 * 刻意不写进 src/main/resources/static：打包后的 jar 里 static 是只读的，运行期写进去既会丢文件，
 * 也会让任何能改工作目录的人往静态资源里塞东西。
 * <p>读取走公开的 /review-photo/{name}：该路径不在 AuthInterceptor 的拦截清单里，
 * 未登录访客也能看到晒单图，而文件名必须命中 {@link UploadPolicy#isGeneratedName} 的随机十六进制格式，
 * 请求路径先 normalize 再判定是否仍在存储目录内，双重拦住路径穿越。
 */
@RestController
@Tag(name = "前台 · 评价图片上传")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final Path baseDir;
    private final long maxBytes;

    public UploadController(@Value("${app.review-upload.dir:upload/review}") String dir,
                            @Value("${app.review-upload.max-size-bytes:5242880}") long maxBytes) {
        this.baseDir = resolveBaseDir(dir);
        // L05：钳制逻辑收进 UploadPolicy，别处再读同一个配置也是同一套上下限
        this.maxBytes = UploadPolicy.clampMaxBytes(maxBytes);
    }

    /** 相对路径按进程工作目录解析，避免同一份配置在 IDE 与 nohup 下落到不同地方 */
    private static Path resolveBaseDir(String dir) {
        Path path = Paths.get(dir);
        return path.isAbsolute() ? path.normalize()
                : Paths.get(System.getProperty("user.dir"), dir).toAbsolutePath().normalize();
    }

    /**
     * 上传单张评价图片（需登录，路径在 /api/reviews/** 的拦截范围内，频次由 L04 的限流规则兜）。
     * 返回的 url 直接塞进评价表单的 images 数组即可。
     */
    @PostMapping("/api/reviews/uploads/image")
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file, HttpSession session) {
        CurrentUser.require(session);
        if (file == null || file.isEmpty()) {
            throw ErrorCode.UPLOAD_EMPTY.ex();
        }
        if (file.getSize() > maxBytes) {
            throw ErrorCode.UPLOAD_TOO_LARGE.ex("图片不超过 " + UploadPolicy.describeLimit(maxBytes));
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw ErrorCode.UPLOAD_TYPE_UNSUPPORTED.ex("只允许上传图片");
        }
        // Content-Type 是客户端自己填的，真正的判定看文件头字节（L05：判定逻辑收在 UploadPolicy，可单测）
        String ext = detectExtension(file);
        if (ext == null) {
            throw ErrorCode.UPLOAD_TYPE_UNSUPPORTED.ex("图片格式不支持，请上传 JPG / PNG / GIF / WEBP");
        }
        String name = UploadPolicy.generatedName("rv", ext);
        Path dir = storageDir();
        Path target = dir.resolve(name);
        // 随机名也再确认一次仍在目录内：配置被改成奇怪值时这里才是最后一道闸
        if (!UploadPolicy.within(dir, target)) {
            log.error("上传目标路径越界，已拒绝");
            throw ErrorCode.UPLOAD_STORAGE_UNAVAILABLE.ex();
        }
        try (InputStream in = file.getInputStream()) {
            // CREATE_NEW：撞上已存在的文件直接失败，绝不静默替换别人的图片；同时逐块核对真实字节数，
            // 防的是「声明 2MB、实际塞 20MB」这类只靠 getSize() 拦不住的把戏
            long written = 0;
            byte[] buffer = new byte[8192];
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    written += read;
                    if (written > maxBytes) {
                        out.close();
                        Files.deleteIfExists(target);
                        throw ErrorCode.UPLOAD_TOO_LARGE.ex("图片不超过 " + UploadPolicy.describeLimit(maxBytes));
                    }
                    out.write(buffer, 0, read);
                }
            }
        } catch (FileAlreadyExistsException e) {
            log.warn("随机文件名撞车，请重试");
            throw ErrorCode.UPLOAD_STORAGE_UNAVAILABLE.ex("图片保存失败，请重新上传");
        } catch (IOException e) {
            log.error("评价图片落盘失败 {}", name, e);
            throw ErrorCode.UPLOAD_STORAGE_UNAVAILABLE.ex("图片保存失败，请稍后重试");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", "/review-photo/" + name);
        data.put("name", name);
        data.put("size", file.getSize());
        data.put("contentType", UploadPolicy.contentType(ext));
        return Result.ok("上传成功", data);
    }

    /**
     * 读取评价图片：文件名必须命中随机命名格式，且解析后的真实路径仍在存储目录内。
     */
    @GetMapping("/review-photo/{fileName}")
    public ResponseEntity<FileSystemResource> photo(@PathVariable String fileName) {
        if (!UploadPolicy.isGeneratedName(fileName)) {
            return ResponseEntity.notFound().build();
        }
        Path dir = storageDir();
        Path file = dir.resolve(fileName).normalize();
        if (!UploadPolicy.within(dir, file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(UploadPolicy.contentType(ext)))
                .contentLength(file.toFile().length())
                // 文件名是随机且不可变的，可以放心让浏览器长期缓存
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(new FileSystemResource(file));
    }

    private Path storageDir() {
        try {
            Files.createDirectories(baseDir);
            return baseDir;
        } catch (IOException e) {
            log.error("评价图片目录不可用", e);
            throw ErrorCode.UPLOAD_STORAGE_UNAVAILABLE.ex();
        }
    }

    /** 按文件头识别真实类型；判定表在 UploadPolicy，扩展名只由识别结果决定，不接受客户端给的后缀 */
    private static String detectExtension(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return UploadPolicy.detectExtension(UploadPolicy.readHead(in, 12));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 自检用（仅管理员）：确认目录可写、配置生效。
     *
     * <p>L05 的口径是「服务器路径不回吐前端」——绝对路径会暴露部署目录与运行账号，
     * 所以这里只回一个稳定的目录指纹（sha-256 前 8 位）：换挂载卷时指纹会变，够运维对账，
     * 又猜不出路径。
     */
    @GetMapping("/api/reviews/uploads/storage")
    public Result<Map<String, Object>> storageInfo(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Path dir = storageDir();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("volume", volumeFingerprint(dir));
        data.put("absolute", baseDir.isAbsolute());
        data.put("writable", Files.isWritable(dir));
        data.put("maxSizeBytes", maxBytes);
        data.put("maxSize", UploadPolicy.describeLimit(maxBytes));
        data.put("allowedTypes", java.util.List.of("image/jpeg", "image/png", "image/gif", "image/webp"));
        return Result.ok(data);
    }

    private static String volumeFingerprint(Path dir) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(dir.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
