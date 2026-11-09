package com.harudle.common.validation;

/** Checks UTF-16 structure without replacing invalid input or exposing its contents. */
public final class UnicodeText {

    private UnicodeText() {
    }

    public static boolean isWellFormed(CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
