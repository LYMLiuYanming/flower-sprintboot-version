package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class UserDtos {

    /**
     * 注册：密码至少含字母与数字两类，配合服务端验证码使用
     */
    public record RegisterRequest(
            @NotBlank(message = "请填写用户名") @Pattern(regexp = "^[\\w\\u4e00-\\u9fa5]{3,20}$", message = "用户名为 3-20 位字母、数字或下划线") String username,
            @NotBlank(message = "请填写密码") @Size(min = 8, max = 32, message = "密码长度需为 8-32 位")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字") String password,
            @NotBlank(message = "请再次输入密码") String confirmPassword,
            @NotBlank(message = "请填写真实姓名") @Size(max = 100) String fullName,
            @NotBlank(message = "请填写手机号") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @Email(message = "邮箱格式不正确") @Size(max = 100) String email,
            @NotBlank(message = "请填写验证码") String captcha,
            String captchaId) {

        @AssertTrue(message = "两次输入的密码不一致")
        public boolean isPasswordConfirmed() {
            return password != null && password.equals(confirmPassword);
        }
    }

    public record ProfileRequest(
            // 手机号可空时只报"请填写手机号"，避免 NotBlank 与 Pattern 双重提示
            @NotBlank(message = "请填写真实姓名") @Size(max = 100) String fullName,
            @NotBlank(message = "请填写手机号") @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String phone,
            @Email(message = "邮箱格式不正确") @Size(max = 100) String email,
            @Pattern(regexp = "^$|^(male|female|other)$", message = "性别取值不合法") String gender,
            LocalDate birthday) {
    }

    /**
     * 修改密码：必须验证旧密码，管理员重置走独立的后台接口
     */
    public record PasswordRequest(
            @NotBlank(message = "请填写原密码") String oldPassword,
            @NotBlank(message = "请填写新密码") @Size(min = 8, max = 32, message = "新密码长度需为 8-32 位")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码需同时包含字母和数字") String newPassword) {
    }

    public record AddressForm(
            @NotBlank(message = "请填写收货人") @Size(max = 50) String receiverName,
            @NotBlank(message = "请填写手机号") @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String receiverPhone,
            @Size(max = 50) String province,
            @Size(max = 50) String city,
            @Size(max = 50) String district,
            @NotBlank(message = "请填写详细地址") @Size(max = 255) String detail,
            @Size(max = 20) String tag,
            Boolean isDefault) {
    }

    public record AdminUserStatusRequest(
            @NotBlank(message = "状态不能为空") @Pattern(regexp = "^(active|inactive|locked)$", message = "状态取值不合法") String status,
            @Size(max = 200) String reason) {
    }

    public record ResetPasswordRequest(
            @Size(min = 8, max = 32, message = "初始密码长度需为 8-32 位") String newPassword,
            @NotBlank(message = "请填写重置理由") @Size(max = 200) String reason) {
    }

    /**
     * 地址智能识别（D10）：前端把整段文本传上来，服务端回拆好的字段供用户核对
     */
    public record AddressParseRequest(
            @NotBlank(message = "请粘贴要识别的地址文本") @Size(max = 500, message = "识别文本不超过 500 字") String raw) {
    }

    /**
     * 批量删除收货地址（D08）
     */
    public record AddressBatchDeleteRequest(
            @NotEmpty(message = "请选择要删除的地址") List<UUID> ids) {
    }

    /**
     * 账户注销申请（D16）：需带确认口令，避免误触与会话劫持下的静默注销
     */
    public record DeletionRequest(
            @NotBlank(message = "请输入登录密码以确认注销") String password) {
    }

    public record ExportRequest(
            @NotBlank(message = "请选择导出类型") @Pattern(regexp = "^(orders|favorites|addresses)$", message = "导出类型不合法") String type) {
    }
}
