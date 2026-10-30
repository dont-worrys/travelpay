package com.travelpay.user.common.security.session;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSessionSerializerTest {

    private final RedisSessionSerializer serializer = new RedisSessionSerializer();

    @Test
    void existingJdkSerializedValuesRemainCompatibleInBothDirections() {
        var old = new JdkSerializationRedisSerializer();
        var principal = new UserSessionPrincipal(UUID.randomUUID(), AuthRole.USER,
                Instant.parse("2026-10-30T00:00:00Z"));

        assertEquals(principal, serializer.deserialize(old.serialize(principal)));
        assertEquals(principal, old.deserialize(serializer.serialize(principal)));
    }

    @Test
    void removedAttributesRemainNullAndAreNotMistakenForUnreadableData() {
        assertNull(serializer.deserialize(null));
        assertNull(serializer.deserialize(new byte[0]));
        assertEquals(0, serializer.serialize(null).length);
        assertFalse(RedisSessionSerializer.isUnreadable(null));
    }

    @Test
    void unreadableBytesUseADistinctMarkerThatCannotBeWrittenBack() {
        var unreadable = serializer.deserialize(new byte[] {1, 2, 3});

        assertTrue(RedisSessionSerializer.isUnreadable(unreadable));
        assertThrows(SerializationException.class, () -> serializer.serialize(unreadable));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aSessionClassMovedByADeploymentIsReportedByItsCauseType(CapturedOutput output) {
        var principal = new UserSessionPrincipal(UUID.randomUUID(), AuthRole.USER,
                Instant.parse("2026-10-30T00:00:00Z"));
        // 같은 길이의 다른 패키지 이름으로 바꿔, 클래스를 옮긴 배포 뒤 기존 세션을 읽는 상황을 만듭니다.
        var moved = new String(serializer.serialize(principal), ISO_8859_1)
                .replace("com.travelpay.user.common.security.session.UserSessionPrincipal",
                        "com.travelpay.user.common.security.removed.UserSessionPrincipal")
                .getBytes(ISO_8859_1);

        assertTrue(RedisSessionSerializer.isUnreadable(serializer.deserialize(moved)));
        assertTrue(output.getAll().contains("세션 값을 읽지 못했습니다: ClassNotFoundException"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theWarningNeverContainsTheStoredValue(CapturedOutput output) {
        var unreadable = serializer.deserialize("not-a-java-object".getBytes(UTF_8));

        assertTrue(RedisSessionSerializer.isUnreadable(unreadable));
        assertTrue(output.getAll().contains("세션 값을 읽지 못했습니다: StreamCorruptedException"));
        // StreamCorruptedException의 메시지는 저장된 앞부분 바이트("not-")를 16진수로 담습니다.
        assertFalse(output.getAll().contains("6E6F742D"));
    }

    @Test
    void serializationFailuresAreNotSilentlyConvertedIntoEmptySessionValues() {
        assertThrows(SerializationException.class, () -> serializer.serialize(new Object()));
    }
}
