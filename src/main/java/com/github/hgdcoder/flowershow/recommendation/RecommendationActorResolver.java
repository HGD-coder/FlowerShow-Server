package com.github.hgdcoder.flowershow.recommendation;

import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public final class RecommendationActorResolver {

    public Actor resolve(Jwt jwt, String installId) {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            String subject = jwt.getSubject().trim();
            if (subject.length() > 128) {
                throw badRequest("INVALID_ACTOR", "JWT subject is too long.");
            }
            return new Actor(sha256Hex("user:" + subject), subject);
        }
        String value = installId == null ? "" : installId.trim();
        if (value.isBlank()) {
            throw badRequest("INSTALL_ID_REQUIRED", "X-Install-Id is required when no verified JWT is supplied.");
        }
        if (value.length() > 256) {
            throw badRequest("INVALID_INSTALL_ID", "X-Install-Id must be at most 256 characters.");
        }
        return new Actor(sha256Hex("install:" + value), null);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static RecommendationException badRequest(String code, String message) {
        return new RecommendationException(HttpStatus.BAD_REQUEST, code, message);
    }
}
