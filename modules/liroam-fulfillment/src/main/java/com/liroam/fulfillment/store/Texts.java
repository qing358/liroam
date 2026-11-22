package com.liroam.fulfillment.store;

/** 写入定长列前截断。 */
public final class Texts {

    private Texts() {
    }

    public static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
