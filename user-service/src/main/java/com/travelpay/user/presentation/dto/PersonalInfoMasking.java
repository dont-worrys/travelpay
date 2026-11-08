package com.travelpay.user.presentation.dto;

/** 관리자 응답의 개인정보를 가립니다. 사람을 구분할 만큼만 남기고 나머지는 *로 바꿉니다. */
public final class PersonalInfoMasking {

    private PersonalInfoMasking() {
    }

    // 글자 수는 가입 때처럼 코드포인트로 셈(이모지처럼 char 2개로 저장되는 글자도 한 글자). 한 글자는 *, 두 글자는 첫 글자만, 그 이상은 첫·끝 글자만 남김
    public static String realName(String realName) {
        var letters = realName.codePoints().toArray();
        if (letters.length == 1) {
            return "*";
        }
        var first = new String(letters, 0, 1);
        if (letters.length == 2) {
            return first + "*";
        }
        return first + "*".repeat(letters.length - 2) + new String(letters, letters.length - 1, 1);
    }

    // @ 앞의 첫 글자만 남기고 별 수를 3개로 고정해 길이도 숨김. 비밀번호 로그인 정보가 없는 회원은 null
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        var firstLetter = email.substring(0, Character.charCount(email.codePointAt(0)));
        return firstLetter + "***" + email.substring(email.lastIndexOf('@'));
    }

    // 저장 형식이 010-0000-0000으로 고정이라 가운데 4자리만 가림
    public static String phone(String phone) {
        return phone.substring(0, 4) + "****" + phone.substring(8);
    }
}
