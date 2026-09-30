package org.liuym.flowerv1springboot.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.controller.UploadController;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F01 评价图片上传的安全边界：类型白名单按文件头而不是客户端声明、大小上限、
 * 随机文件名、读取侧拒绝路径穿越、未登录不接受上传。
 * 用 @TempDir 作为存储根目录，不碰 static 资源目录。
 */
class ReviewUploadGuardTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 73, 68, 65, 84};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F',
            'I', 'F', 0, 1, 0, 0};

    @TempDir
    Path storageRoot;

    private UploadController controller;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        controller = new UploadController(storageRoot.toString(), 5 * 1024 * 1024);
        session = new MockHttpSession();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("tester");
        user.setStatus("active");
        session.setAttribute(CurrentUser.SESSION_KEY, user);
    }

    @Test
    void 未登录不接受上传() {
        MockHttpSession anonymous = new MockHttpSession();
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.uploadImage(new MockMultipartFile("file", "a.png", "image/png", PNG), anonymous));
        assertEquals(401, e.getCode());
    }

    @Test
    void 真实png文件头才被认下并随机命名() throws Exception {
        Map<String, Object> data = controller.uploadImage(
                new MockMultipartFile("file", "我的自拍.png", "image/png", PNG), session).getData();
        assertNotNull(data);
        String name = String.valueOf(data.get("name"));
        assertTrue(name.matches("^rv_[0-9a-f]{32}\\.png$"), "文件名要随机且只由识别出的类型决定：" + name);
        assertEquals("/review-photo/" + name, data.get("url"));
        assertEquals("image/png", data.get("contentType"));
        // 原始文件名不参与落盘，中文名/相对路径都无法影响存储位置
        assertFalse(Files.exists(storageRoot.resolve("我的自拍.png")));
        assertTrue(Files.isRegularFile(storageRoot.resolve(name)));
    }

    @Test
    void 伪装成图片的脚本按文件头拒绝() {
        MockMultipartFile script = new MockMultipartFile("file", "evil.png", "image/png",
                "<?php echo 1; ?>xxxxxxxx".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BusinessException e = assertThrows(BusinessException.class, () -> controller.uploadImage(script, session));
        assertTrue(e.getMessage().contains("格式"), e.getMessage());
    }

    @Test
    void 非image类型一律拒绝() {
        MockMultipartFile txt = new MockMultipartFile("file", "note.txt", "text/plain",
                "花很新鲜花很新鲜花很新鲜花很新鲜".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> controller.uploadImage(txt, session));
    }

    @Test
    void 超过上限的图片被拒绝() {
        byte[] big = new byte[4096];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        UploadController tiny = new UploadController(storageRoot.toString(), 1024);
        BusinessException e = assertThrows(BusinessException.class,
                () -> tiny.uploadImage(new MockMultipartFile("file", "big.jpg", "image/jpeg", big), session));
        assertTrue(e.getMessage().contains("MB"), e.getMessage());
    }

    @Test
    void jpeg文件头识别成jpg() {
        Map<String, Object> data = controller.uploadImage(
                new MockMultipartFile("file", "x.jpg", "image/jpeg", JPEG), session).getData();
        assertTrue(String.valueOf(data.get("name")).endsWith(".jpg"));
    }

    @Test
    void 读取侧拒绝穿越与非法文件名() {
        assertEquals(404, controller.photo("../../application.properties").getStatusCode().value());
        assertEquals(404, controller.photo("evil.jsp").getStatusCode().value());
        assertEquals(404, controller.photo("rv_zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.png").getStatusCode().value());
        assertEquals(404, controller.photo("../upload/review/rv_00000000000000000000000000000000.png")
                .getStatusCode().value());
    }

    @Test
    void 读取已上传的图片返回内容与缓存头() {
        Map<String, Object> data = controller.uploadImage(
                new MockMultipartFile("file", "photo.png", "image/png", PNG), session).getData();
        String name = String.valueOf(data.get("name"));
        ResponseEntity<org.springframework.core.io.FileSystemResource> response = controller.photo(name);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertNotNull(response.getBody());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("max-age=2592000"),
                response.getHeaders().getCacheControl());
    }

    /**
     * 存储自检只回「卷指纹 + 是否可写」，不再回服务器绝对路径（L05）：
     * 路径会暴露部署目录与运行账号，而指纹换挂载卷时会变，够运维对账。
     * 该接口现在只面向管理员。
     */
    @Test
    void 存储自检不泄露服务器路径() {
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("ops");
        admin.setStatus("active");
        admin.setUserType(User.TYPE_ADMIN);
        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute(CurrentUser.SESSION_KEY, admin);

        Map<String, Object> info = controller.storageInfo(adminSession).getData();
        assertFalse(info.containsKey("dir"), "响应里不能再出现服务器路径");
        assertEquals(5 * 1024 * 1024, info.get("maxSizeBytes"));
        assertEquals(Boolean.TRUE, info.get("writable"));
        assertNotNull(info.get("volume"));

        // 普通登录用户拿不到这份信息
        assertThrows(BusinessException.class, () -> controller.storageInfo(session));
    }
}
