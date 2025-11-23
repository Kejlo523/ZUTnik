package pl.kejlo.mzutv2;

import java.security.SecureRandom;
import java.util.Calendar;

public class MzutTokenGenerator {

    private static final char[] CARR = new char[]{
            '2','3','4','5','6','7','8','9',
            'a','b','c','d','e','f','g','h','i','j','k',
            'm','n','o','p','q','r','s','t','u','v','w','x','y','z',
            'A','B','C','D','E','F','G','H',
            'J','K','L','M','N','P','Q','R','S','T','U','V','W','X','Y','Z'
    };

    private static final char[] CARR2 = new char[]{
            'v','w','x','y','z',
            '2','3','4','5','6','7','8','9',
            'A','B','C','D','E','F','G','H','J','K',
            'k','m','n','o','p','q','r','s','t','u',
            'a','b','c','d','e','f','g','h','i','j',
            'W','X','Y','Z','L','M','N','P','Q','R','S','T','U','V'
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateToken(String login, String password) {
        String string = "p4Hb7BwxUDqaiBATQe7KRhvBQuh2TY2j";

        try {
            // losowy string 32 znaki z CARR
            StringBuilder buf = new StringBuilder();
            int length = CARR.length;
            for (int i = 0; i < 32; i++) {
                int idx = RANDOM.nextInt(length);
                buf.append(CARR[idx]);
            }
            string = buf.toString();

            if (password == null || password.length() == 0) {
                return string;
            }

            Calendar cal = Calendar.getInstance();
            int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH); // 1..31

            int phpW = cal.get(Calendar.DAY_OF_WEEK) - 1;    // 0..6
            if (phpW < 0) phpW = 0;
            int dayOfWeek = phpW + 1;                        // 1..7

            int dayOfWeekInMonth = (dayOfMonth - 1) / 7 + 1;

            String str3 = (login == null ? "" : login) + password;
            int len = str3.length();

            int[] iArr = new int[]{
                    len - 1,
                    len - 5,
                    len - 8,
                    dayOfMonth,
                    dayOfWeek,
                    dayOfWeekInMonth
            };

            int sum = 0;
            for (int v : iArr) sum += v;

            char[] cArrLocal = CARR;

            if (sum % 2 == 0) {
                iArr[0] = dayOfMonth;
                iArr[1] = len + 3;
                iArr[2] = len + 9;
                iArr[3] = dayOfWeek;
                iArr[4] = len;
                iArr[5] = dayOfWeekInMonth;
                cArrLocal = CARR2;
            }

            StringBuilder result = new StringBuilder();
            int strLen = string.length();

            for (int i2 = 0; i2 < strLen; i2++) {
                char ch = string.charAt(i2);
                if (iArr[0] == i2 && iArr[0] <= 32 && iArr[0] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[0]]);
                } else if (iArr[1] == i2 && iArr[1] <= 32 && iArr[1] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[1]]);
                } else if (iArr[2] == i2 && iArr[2] <= 32 && iArr[2] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[2]]);
                } else if (iArr[3] == i2 && iArr[3] <= 32 && iArr[3] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[3]]);
                } else if (iArr[4] == i2 && iArr[4] <= 32 && iArr[4] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[4]]);
                } else if (iArr[5] == i2 && iArr[5] <= 32 && iArr[5] < cArrLocal.length) {
                    result.append(cArrLocal[iArr[5]]);
                } else {
                    result.append(ch);
                }
            }

            return result.toString();
        } catch (Exception e) {
            return string;
        }
    }
}

