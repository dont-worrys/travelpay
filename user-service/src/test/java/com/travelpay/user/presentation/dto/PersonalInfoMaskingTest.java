package com.travelpay.user.presentation.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonalInfoMaskingTest {

    @ParameterizedTest
    @CsvSource({"홍,*", "홍길,홍*", "홍길동,홍*동", "남궁민수,남**수", "John Smith,J********h"})
    void realNamesKeepOnlyTheFirstAndLastLetter(String realName, String masked) {
        assertEquals(masked, PersonalInfoMasking.realName(realName));
    }

    @Test
    void aLetterStoredAsTwoJavaCharsIsMaskedAsOneLetter() {
        assertEquals("😀*😀", PersonalInfoMasking.realName("😀가😀"));
        assertEquals("😀*", PersonalInfoMasking.realName("😀가"));
    }

    @ParameterizedTest
    @CsvSource({"member@example.com,m***@example.com", "a@example.com,a***@example.com",
            "😀member@example.com,😀***@example.com"})
    void emailsKeepTheFirstLetterAndTheDomainWithAFixedNumberOfStars(String email, String masked) {
        assertEquals(masked, PersonalInfoMasking.email(email));
    }

    @Test
    void aMemberWithoutPasswordLoginHasNoEmailToMask() {
        assertNull(PersonalInfoMasking.email(null));
    }

    @Test
    void phonesHideTheMiddleFourDigits() {
        assertEquals("010-****-5678", PersonalInfoMasking.phone("010-1234-5678"));
    }
}
