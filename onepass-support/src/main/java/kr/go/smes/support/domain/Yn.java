package kr.go.smes.support.domain;

public final class Yn {

    public static final String YES = "Y";
    public static final String NO = "N";

    private Yn() {
    }

    public static String fromBoolean(boolean value) {
        return value ? YES : NO;
    }

    public static boolean isYes(String value) {
        return YES.equals(value);
    }
}

