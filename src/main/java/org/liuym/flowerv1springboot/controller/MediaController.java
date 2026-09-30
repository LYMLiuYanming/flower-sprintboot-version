package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.vo.MediaViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 媒体资产接口（J 组）：立体展台的品类→GLB 映射 + 站内图片的缩略图出口。
 *
 * <p>映射表是只读字典、不参与交易主链路，所以直接在这里用原生 SQL 读 media_model_map，
 * 命中判定与参数校验都写成静态纯函数（单测不需要起 Spring）。后续要后台维护再抽 Service 层不迟。
 *
 * <p>/img/thumb 是站内图片唯一的「重采样」出口：只允许白名单目录、只接受站内相对路径、
 * 尺寸强制夹在合理区间，越界一律 4xx（不是 JSON 200），这样前 onerror 占位与浏览器缓存都能看到真实失败。
 */
@RestController
@Tag(name = "前台 · 媒体资产")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    /** 允许出缩略图的目录前缀：商品实拍、兜底插画、轮播图。其余目录（含 /models）一律拒绝 */
    public static final Set<String> THUMB_WHITELIST = Set.of("/img/photos/", "/img/products/", "/img/banners/");

    /** 站点根下的固定占位图：只按整串精确放行，不开 /img/ 整个目录，免得白名单被前缀绕开 */
    public static final Set<String> THUMB_ALLOWED_FILES = Set.of(
            "/img/placeholder.svg", "/img/product-placeholder.svg", "/img/favicon.svg");

    /** 位图才需要重采样；SVG 是矢量的，缩放没意义，原样透出 */
    public static final Set<String> RASTER_EXT = Set.of("jpg", "jpeg", "png", "bmp", "gif");
    public static final Set<String> VECTOR_EXT = Set.of("svg");

    public static final int THUMB_MIN_WIDTH = 64;
    public static final int THUMB_MAX_WIDTH = 1600;
    public static final int THUMB_DEFAULT_WIDTH = 640;
    public static final int THUMB_MAX_PIXELS = 1_800_000;

    /** 已解码源图缓存：一张 1024 宽实拍解码后约 5MB，48 张足够覆盖在售 SKU 的主图 */
    private static final int SOURCE_CACHE_SIZE = 48;
    /** 成品缩略图字节缓存：命中即免掉一次解码 + 重采样 + 编码 */
    private static final int THUMB_CACHE_SIZE = 120;

    private final EntityManager entityManager;
    private final Map<String, byte[]> thumbCache = boundedCache(THUMB_CACHE_SIZE);
    private final Map<String, BufferedImage> sourceCache = boundedCache(SOURCE_CACHE_SIZE);

    public MediaController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping("/api/media/models")
    @Operation(summary = "模型映射目录", description = "media_model_map 全量启用行与全局兜底行，供展台与后台对照")
    public Result<MediaViews.ModelCatalog> catalog() {
        List<MediaViews.ModelBinding> bindings = loadBindings();
        MediaViews.ModelBinding fallback = bindings.stream()
                .filter(b -> "default".equals(b.matchType()))
                .findFirst()
                .orElse(null);
        return Result.ok(new MediaViews.ModelCatalog(bindings.size(), bindings, fallback));
    }

    @GetMapping("/api/media/models/resolve")
    @Operation(summary = "解析品类对应模型",
            description = "关键词 → 分类 → 全局兜底三档命中；三档都没命中时 modelUrl 为空，展台回落实拍图集")
    public Result<MediaViews.Stage> resolve(@RequestParam(required = false) String category,
                                           @RequestParam(required = false) String keyword) {
        return Result.ok(resolveStage(loadBindings(), category, keyword));
    }

    /**
     * 站内图片缩略图：J11 的落点，列表页只拉小图、详情页按需拉大图都走这里。
     * 源图不比请求宽度大时原样返回，不做无谓的放大重采样。
     */
    @GetMapping("/img/thumb")
    @Operation(summary = "图片缩略图", description = "白名单目录内的站内图片，按宽度重采样后长缓存")
    public ResponseEntity<byte[]> thumb(@RequestParam String path,
                                       @RequestParam(required = false) Integer w,
                                       @RequestParam(required = false) Integer h,
                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        MediaViews.ThumbRequest request;
        Resource resource;
        String etag;
        try {
            request = normalizeThumb(path, w, h);
            resource = new ClassPathResource("static" + request.path());
            if (!resource.isReadable()) {
                throw BusinessException.notFound("图片不存在");
            }
            etag = fingerprint(resource, request);
        } catch (BusinessException ex) {
            // 参数越界/文件缺失要真返 4xx：返回 JSON 200 会让 <img> 以为加载成功，占位与重试都失效
            return ResponseEntity.status(ex.getCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST).build();
        }

        if (etag != null && etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String cacheKey = request.path() + '|' + request.width() + '|' + request.height();
        byte[] body = thumbCache.get(cacheKey);
        if (body == null) {
            try {
                body = Boolean.TRUE.equals(request.passthrough())
                        ? readAll(resource)
                        : encode(resize(loadRaster(resource), request.width(), request.height()));
            } catch (BusinessException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            if (body.length == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            thumbCache.put(cacheKey, body);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType(request.path()));
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable());
        if (etag != null) {
            headers.setETag(etag);
        }
        if (VECTOR_EXT.contains(extension(request.path()))) {
            // SVG 与站点同源返回，沙箱化避免素材里夹的脚本被当成同源页面执行
            headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // ------------------------------------------------------------------ 纯函数（单测覆盖）

    /**
     * 三档命中：关键词最具体（同分类里花盒与花束差很远），其次分类，最后全局兜底。
     * 大小写与首尾空格忽略，运营手写的 match_key 带空格也能命中。
     *
     * @return 命中的 Stage（matched 标明命中档位）；三档都没有时 modelUrl 为空
     */
    public static MediaViews.Stage resolveStage(List<MediaViews.ModelBinding> bindings, String category, String keyword) {
        MediaViews.ModelBinding byKeyword = null;
        MediaViews.ModelBinding byCategory = null;
        MediaViews.ModelBinding fallback = null;
        String left = lower(keyword);
        String cat = lower(category);

        for (MediaViews.ModelBinding binding : bindings) {
            String key = lower(binding.matchKey());
            if (key.isEmpty() || binding.matchType() == null) {
                continue;
            }
            switch (binding.matchType()) {
                case "keyword" -> {
                    if (!left.isEmpty() && left.contains(key) && prefer(byKeyword, binding)) {
                        byKeyword = binding;
                    }
                }
                // 分类名允许互相包含（配到「玫瑰」就能覆盖「玫瑰花/香槟玫瑰」），取更长的键以保留更精确的配置
                case "category" -> {
                    if (!cat.isEmpty() && (cat.equals(key) || cat.contains(key)) && prefer(byCategory, binding)) {
                        byCategory = binding;
                    }
                }
                case "default" -> {
                    if (fallback == null) {
                        fallback = binding;
                    }
                }
                default -> log.warn("未知的模型映射档位 {}，已跳过", binding.matchType());
            }
        }

        MediaViews.ModelBinding winner = byKeyword != null ? byKeyword : byCategory != null ? byCategory : fallback;
        if (winner == null) {
            return new MediaViews.Stage(null, null, null, null, null, null, null,
                    "该品类暂未配置三维模型，已切到实拍图集");
        }
        String matched = byKeyword != null ? "keyword" : byCategory != null ? "category" : "default";
        String label = blank(winner.label()) ? "立体展台" : winner.label();
        String caption = blank(winner.caption())
                ? "公共领域（CC0）通用模型，仅用于感受体量与层次，实物花材以门店当日为准。"
                : winner.caption();
        return new MediaViews.Stage(winner.modelUrl(), label, caption,
                blank(winner.preset()) ? "full" : winner.preset(),
                winner.spinSpeed() != null ? winner.spinSpeed() : 0.32d,
                winner.shared() != null ? winner.shared() : Boolean.TRUE, matched, null);
    }

    /** 同档位多条命中时取 match_key 更长的一条：配得越具体越该生效 */
    private static boolean prefer(MediaViews.ModelBinding current, MediaViews.ModelBinding candidate) {
        if (candidate.matchKey() == null) {
            return false;
        }
        return current == null || candidate.matchKey().trim().length() > current.matchKey().trim().length();
    }

    /**
     * 缩略图参数校验：只接受白名单目录内的站内相对路径，尺寸夹进 [64,1600]。
     * ../、编码后的 ../、绝对 URL、协议相对路径都在这里挡掉，不做「看似无害的兜底转换」。
     *
     * @throws BusinessException 参数越界
     */
    public static MediaViews.ThumbRequest normalizeThumb(String rawPath, Integer width, Integer height) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new BusinessException("缺少图片路径");
        }
        String path = rawPath.trim();
        // 控制字符在 trim 之前就判掉：允许首尾空白被裁，但不接受路径中间夹 \t \n 之类的变体
        if (rawPath.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new BusinessException("图片路径含非法字符");
        }
        if (!path.startsWith("/") || path.startsWith("//")
                || path.contains("..") || path.contains("\\") || path.contains("%")
                || path.contains("?") || path.contains("#") || path.contains(":")) {
            throw new BusinessException("图片路径需为站内白名单路径");
        }
        String normalized = path.replaceAll("/{2,}", "/");
        boolean allowed = THUMB_WHITELIST.stream().anyMatch(normalized::startsWith)
                || THUMB_ALLOWED_FILES.contains(normalized);
        if (!allowed) {
            throw new BusinessException("该目录不提供缩略图服务");
        }
        String ext = extension(normalized);
        boolean vector = VECTOR_EXT.contains(ext);
        if (!vector && !RASTER_EXT.contains(ext)) {
            throw new BusinessException("仅支持 jpg/png/gif/bmp/svg 图片");
        }

        Integer w = clampWidth(width);
        Integer h = clampHeight(height, w);
        return new MediaViews.ThumbRequest(normalized, w, vector ? null : h, vector);
    }

    /** 宽度缺省 640，越界夹进 [64,1600]：上限既是内存保护，也防止被人当开放裁图代理用 */
    public static Integer clampWidth(Integer width) {
        if (width == null || width <= 0) {
            return THUMB_DEFAULT_WIDTH;
        }
        return Math.max(THUMB_MIN_WIDTH, Math.min(THUMB_MAX_WIDTH, width));
    }

    /** 高度可省略（按源图比例推）；给了就同样夹区间，并限制总像素数 */
    public static Integer clampHeight(Integer height, Integer width) {
        if (height == null || height <= 0) {
            return null;
        }
        int h = Math.max(THUMB_MIN_WIDTH, Math.min(THUMB_MAX_WIDTH, height));
        if ((long) h * width > THUMB_MAX_PIXELS) {
            throw new BusinessException("请求的图片尺寸过大");
        }
        return h;
    }

    public static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash + 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------ 内部实现

    /** 映射表只有几十行，一次全量读出来在内存里判档位，比三条带条件的 SQL 更好测也好排查 */
    private List<MediaViews.ModelBinding> loadBindings() {
        List<?> rows;
        try {
            Query query = entityManager.createNativeQuery(
                    "SELECT match_type, match_key, model_url, label, caption, preset, spin_speed, is_shared "
                            + "FROM media_model_map WHERE is_active = true ORDER BY sort_order, match_type");
            rows = query.getResultList();
        } catch (RuntimeException ex) {
            // V25 没执行（老库/新环境）时不该让商品页 500，降级成「无模型 → 2D 图集」即可
            log.warn("读取模型映射失败，立体展台将回落实拍图集：{}", ex.getMessage());
            return List.of();
        }
        List<MediaViews.ModelBinding> bindings = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            bindings.add(new MediaViews.ModelBinding(
                    str(cells[0]), str(cells[1]), str(cells[2]), str(cells[3]), str(cells[4]), str(cells[5]),
                    num(cells[6]), bool(cells[7]), null));
        }
        return bindings;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double num(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 弱校验器：只由源文件标识 + 请求尺寸组成，不含重采样结果，
     * 所以命中 304 时连解码都能省掉。文件取不到时间戳（打进 jar）时退化成路径 + 长度。
     */
    private static String fingerprint(Resource resource, MediaViews.ThumbRequest request) {
        try {
            long stamp = resource.lastModified();
            long size = stamp > 0 ? stamp : resource.contentLength();
            return "W/\"t" + Long.toString(size, 36) + '-' + request.width() + 'x' + request.height() + '"';
        } catch (IOException ex) {
            return null;
        }
    }

    private static MediaType contentType(String path) {
        return switch (extension(path)) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            default -> MediaType.IMAGE_JPEG;
        };
    }

    private byte[] readAll(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            log.warn("站内图片读取失败：{}", ex.getMessage());
            return new byte[0];
        }
    }

    /** 源图解码有缓存：同一张主图被不同尺寸请求时只付一次 ImageIO 解码成本 */
    private BufferedImage loadRaster(Resource resource) {
        String key = describe(resource);
        BufferedImage cached = sourceCache.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage image;
        try (InputStream in = resource.getInputStream()) {
            image = ImageIO.read(in);
        } catch (IOException ex) {
            image = null;
        }
        if (image == null) {
            throw BusinessException.notFound("图片无法解析");
        }
        sourceCache.put(key, image);
        return image;
    }

    private static String describe(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException ex) {
            return resource.getFilename() == null ? "thumb" : resource.getFilename();
        }
    }

    /** 只缩不放：源图已经不比目标小就原样返回，避免列表页小图把实拍放大再糊掉 */
    public static BufferedImage resize(BufferedImage source, Integer width, Integer height) {
        double ratio = width == null ? 1 : (double) width / source.getWidth();
        if (height != null) {
            ratio = Math.min(ratio, (double) height / source.getHeight());
        }
        if (ratio >= 1) {
            return source;
        }
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        BufferedImage current = source;
        // 逐级减半再最后一次精修：一步到位的大幅缩放会明显丢高频细节（花瓣边缘发锯齿）
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = scaleTo(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return scaleTo(current, targetWidth, targetHeight);
    }

    private static BufferedImage scaleTo(BufferedImage source, int width, int height) {
        boolean alpha = source.getColorModel().hasAlpha();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!alpha) {
            // PNG 转 JPEG 时透明区不铺底会变成纯黑，花艺实拍图上的白底更贴近原设计
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        }
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage image) {
        boolean alpha = image.getColorModel().hasAlpha();
        String format = alpha ? "png" : "jpg";
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // 0.82 是实拍花材的经验值：再低会让雾面包装纸的颗粒糊成色块
                param.setCompressionQuality(alpha ? 1f : 0.82f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException ex) {
            log.warn("缩略图编码失败：{}", ex.getMessage());
            return new byte[0];
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    /** 容量上限的 LRU：缩略图读多写零，不值得为它引一层缓存框架 */
    private static <V> Map<String, V> boundedCache(int limit) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > limit;
            }
        });
    }
}
