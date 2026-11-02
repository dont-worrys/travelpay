package com.travelpay.payment.transfer.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipientNameMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "홍, *",
            "이산, 이*",
            "홍길동, 홍*동",
            "남궁민수, 남**수",
            "'  홍길동  ', 홍*동",
            "John Smith, J********h"
    })
    void keepsOnlyFirstAndLastCharacters(String name, String expected) {
        assertEquals(expected, RecipientNameMasker.mask(name));
    }
}
