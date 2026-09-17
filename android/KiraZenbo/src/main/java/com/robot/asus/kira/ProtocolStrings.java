package com.robot.asus.kira;

/** JSON Schema string-length helpers; maxLength is measured in Unicode code points. */
final class ProtocolStrings {
    private ProtocolStrings() {
    }

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    static String truncate(String value, int maximumCodePoints) {
        if (length(value) <= maximumCodePoints) return value;
        int end = value.offsetByCodePoints(0, maximumCodePoints);
        return value.substring(0, end);
    }
}
