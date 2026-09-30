package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPolicyTest {

    @Test
    void dictionariesHaveUniqueCodesAndReadableNames() {
        List<String> placementCodes = DeliveryPolicy.PLACEMENTS.stream().map(DeliveryPolicy.Placement::code).toList();
        List<String> contactCodes = DeliveryPolicy.CONTACTS.stream().map(DeliveryPolicy.Contact::code).toList();
        assertEquals(placementCodes.size(), placementCodes.stream().distinct().count());
        assertEquals(contactCodes.size(), contactCodes.stream().distinct().count());
        DeliveryPolicy.PLACEMENTS.forEach(p -> {
            assertFalse(p.name().isBlank());
            assertFalse(p.note().isBlank());
            assertFalse(p.icon().isBlank());
        });
        DeliveryPolicy.CONTACTS.forEach(c -> assertFalse(c.name().isBlank()));
    }

    @Test
    void lookupIgnoresCaseAndSurroundingSpace() {
        assertEquals("door", DeliveryPolicy.findPlacement("  DOOR ").orElseThrow().code());
        assertEquals("quiet", DeliveryPolicy.findContact("QUIET").orElseThrow().code());
    }

    @Test
    void unknownCodeFallsBackToDefault() {
        assertEquals(DeliveryPolicy.DEFAULT_PLACEMENT.code(), DeliveryPolicy.placement("rooftop").code());
        assertEquals(DeliveryPolicy.DEFAULT_PLACEMENT.code(), DeliveryPolicy.placement(null).code());
        assertEquals(DeliveryPolicy.DEFAULT_CONTACT.code(), DeliveryPolicy.contact("").code());
    }

    @Test
    void onlyWhitelistedCodesArePersisted() {
        assertEquals("locker", DeliveryPolicy.placementCodeOrNull("Locker"));
        assertNull(DeliveryPolicy.placementCodeOrNull("drone"), "字典外的值应丢弃而不是落库");
        assertNull(DeliveryPolicy.placementCodeOrNull(null));
        assertNull(DeliveryPolicy.contactCodeOrNull("fax"));
    }

    @Test
    void summaryJoinsOnlyWhatUserPicked() {
        assertEquals("放门口·请勿联系", DeliveryPolicy.summary("door", "quiet"));
        assertEquals("放门口", DeliveryPolicy.summary("door", null));
        assertEquals("送达前电话联系", DeliveryPolicy.summary(null, "call"));
        assertNull(DeliveryPolicy.summary(null, null));
        assertNull(DeliveryPolicy.summary("unknown", "nope"), "两个都是野值时不该输出偏好");
    }

    @Test
    void namesResolveFromCodeForViews() {
        assertEquals("放快递柜", DeliveryPolicy.placementName("locker"));
        assertEquals("到了发短信", DeliveryPolicy.contactName("msg"));
        // 历史订单没落库偏好时视图仍给得出可读文案
        assertEquals("当面签收", DeliveryPolicy.placementName(null));
    }
}
