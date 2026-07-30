package com.github.hgdcoder.flowershow.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(long sortValue, String id) {
        String raw = sortValue + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor.trim()),
                    StandardCharsets.UTF_8
            );
            int separator = decoded.indexOf('\n');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("Missing cursor separator.");
            }
            return new Cursor(
                    Long.parseLong(decoded.substring(0, separator)),
                    decoded.substring(separator + 1)
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid pagination cursor.", e);
        }
    }

    public record Cursor(long sortValue, String id) {
    }
}
