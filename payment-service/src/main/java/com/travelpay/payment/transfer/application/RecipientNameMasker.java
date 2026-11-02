package com.travelpay.payment.transfer.application;

/**
 * 수취인 확인 화면에 보여 줄 이름을 마스킹합니다.
 * 2자는 첫 글자만(홍*), 3자 이상은 첫 글자와 끝 글자만 남깁니다(홍*동, 남**수). 1자는 모두 가립니다.
 * 띄어쓰기도 가운데 글자로 보고 가려 단어 길이가 드러나지 않게 합니다.
 */
final class RecipientNameMasker {

    private static final String MASK = "*";

    private RecipientNameMasker() {
    }

    static String mask(String name) {
        int[] codePoints = name.strip().codePoints().toArray();
        int length = codePoints.length;
        if (length <= 1) {
            return MASK;
        }
        String first = new String(codePoints, 0, 1);
        if (length == 2) {
            return first + MASK;
        }
        return first + MASK.repeat(length - 2) + new String(codePoints, length - 1, 1);
    }
}
