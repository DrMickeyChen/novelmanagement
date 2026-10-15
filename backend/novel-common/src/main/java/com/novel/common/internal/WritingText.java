package com.novel.common.internal;

import com.novel.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class WritingText {
    private WritingText() {}
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void range(String text, int start, int end) {
        if (start < 0 || end < start || end > text.length() || split(text,start) || split(text,end))
            throw new BusinessException(422,"选区无效，不能切断字符",422);
    }
    private static boolean split(String text, int pos) {
        return pos > 0 && pos < text.length() && Character.isHighSurrogate(text.charAt(pos-1))
                && Character.isLowSurrogate(text.charAt(pos));
    }
    public static String replace(String text, int start, int end, String original, String replacement) {
        range(text,start,end);
        if (!text.substring(start,end).equals(original)) throw new BusinessException(409,"选区原文已变化",409);
        return text.substring(0,start)+replacement+text.substring(end);
    }
}
