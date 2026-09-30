package org.liuym.flowerv1springboot.common;

import java.util.List;
import java.util.Optional;

/**
 * 送达偏好字典：放置位置与联系方式都是封闭枚举，页面按字典渲染，服务端只认 code。
 *
 * <p>偏好不改变运费口径（放快递柜不额外收费），只约束配送员动作，因此与 ShippingPolicy 分开维护。
 */
public final class DeliveryPolicy {

    private DeliveryPolicy() {
    }

    /** 放置位置 */
    public record Placement(String code, String name, String note, String icon) {
    }

    /** 联系收花人方式 */
    public record Contact(String code, String name, String note, String icon) {
    }

    public static final Placement MEET_IN_PERSON = new Placement("meet", "当面签收", "交给收花人本人，最稳妥", "fa-user-check");
    public static final Placement DOORSTEP = new Placement("door", "放门口", "无接触配送，放门口后拍照回执", "fa-door-closed");
    public static final Placement FRONT_DESK = new Placement("front", "放前台 / 门卫", "无人接收时交给代收点", "fa-bell-concierge");
    public static final Placement LOCKER = new Placement("locker", "放快递柜", "柜号由配送员送达后短信告知", "fa-boxes-stacked");

    public static final List<Placement> PLACEMENTS = List.of(MEET_IN_PERSON, DOORSTEP, FRONT_DESK, LOCKER);

    public static final Contact CALL_BEFORE = new Contact("call", "送达前电话联系", "配送员出发前 30 分钟致电", "fa-phone");
    public static final Contact MESSAGE_ONLY = new Contact("msg", "到了发短信", "全程不打电话，短信告知送达", "fa-comment-dots");
    public static final Contact NO_CONTACT = new Contact("quiet", "请勿联系", "收花人在开会或休息，静音放置", "fa-bell-slash");

    public static final List<Contact> CONTACTS = List.of(CALL_BEFORE, MESSAGE_ONLY, NO_CONTACT);

    public static final Placement DEFAULT_PLACEMENT = MEET_IN_PERSON;
    public static final Contact DEFAULT_CONTACT = CALL_BEFORE;

    /** 门店自提没有放置与联系问题，页面据此隐藏偏好区 */
    public static final String SELF_PICKUP_METHOD = "self_pickup";

    public static Optional<Placement> findPlacement(String code) {
        return code == null || code.isBlank()
                ? Optional.empty()
                : PLACEMENTS.stream().filter(p -> p.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    public static Optional<Contact> findContact(String code) {
        return code == null || code.isBlank()
                ? Optional.empty()
                : CONTACTS.stream().filter(c -> c.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    /** 历史订单与非法码统一按「当面签收」降级，页面无需再判空 */
    public static Placement placement(String code) {
        return findPlacement(code).orElse(DEFAULT_PLACEMENT);
    }

    public static Contact contact(String code) {
        return findContact(code).orElse(DEFAULT_CONTACT);
    }

    /** 只有命中字典才落库，未选保持 null，以区分「用户显式选择」与「沿用默认」 */
    public static String placementCodeOrNull(String code) {
        return findPlacement(code).map(Placement::code).orElse(null);
    }

    public static String contactCodeOrNull(String code) {
        return findContact(code).map(Contact::code).orElse(null);
    }

    public static String placementName(String code) {
        return placement(code).name();
    }

    public static String contactName(String code) {
        return contact(code).name();
    }

    /** 偏好摘要文本：只有一方有值时也输出，供轨迹与后台面单展示 */
    public static String summary(String placementCode, String contactCode) {
        Placement placement = findPlacement(placementCode).orElse(null);
        Contact contact = findContact(contactCode).orElse(null);
        if (placement == null && contact == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (placement != null) {
            text.append(placement.name());
        }
        if (contact != null) {
            if (!text.isEmpty()) {
                text.append('·');
            }
            text.append(contact.name());
        }
        return text.toString();
    }
}
