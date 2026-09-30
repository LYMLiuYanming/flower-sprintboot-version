package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyPolicyTest {

    @Test
    void tokensAreHexUniqueAndWithinColumnLength() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String token = IdempotencyPolicy.newToken();
            assertEquals(48, token.length());
            assertTrue(token.matches("^[0-9a-f]{48}$"), token);
            assertTrue(seen.add(token), "随机凭证出现重复");
        }
    }

    @Test
    void tokenStaysUsableUntilTtlBoundary() {
        LocalDateTime issued = LocalDateTime.of(2026, 9, 27, 22, 0);
        assertFalse(IdempotencyPolicy.expired(issued, issued.plusMinutes(IdempotencyPolicy.TTL_MINUTES - 1)));
        assertTrue(IdempotencyPolicy.expired(issued, issued.plusMinutes(IdempotencyPolicy.TTL_MINUTES + 1)));
    }

    @Test
    void missingCreatedAtCountsAsExpired() {
        assertTrue(IdempotencyPolicy.expired(null, LocalDateTime.now()));
    }
}
