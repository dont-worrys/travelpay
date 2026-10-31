package com.travelpay.user.application.dto;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UserRealNameResultTest {

    @Test
    void stringRepresentationDoesNotExposeTheRealName() {
        var result = new UserRealNameResult(UUID.randomUUID(), "홍길동");

        assertFalse(result.toString().contains("홍길동"));
    }
}
