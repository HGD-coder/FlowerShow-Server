package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public final class RecommendationTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final String CURSOR_DOMAIN = "flowershow/recommendation/cursor/v1";
    private static final String EXPOSURE_DOMAIN = "flowershow/recommendation/exposure/v1";

    private final byte[] secret;
    private final ObjectMapper objectMapper;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public RecommendationTokenService(SecretKey jwtSecretKey, ObjectMapper objectMapper) {
        this.secret = jwtSecretKey.getEncoded().clone();
        this.objectMapper = objectMapper;
    }

    public String signCursor(CursorClaims claims) {
        return sign(CURSOR_DOMAIN, claims);
    }

    public CursorClaims verifyCursor(String token) {
        return verify(CURSOR_DOMAIN, token, CursorClaims.class);
    }

    public String signExposure(ExposureClaims claims) {
        return sign(EXPOSURE_DOMAIN, claims);
    }

    public ExposureClaims verifyExposure(String token) {
        return verify(EXPOSURE_DOMAIN, token, ExposureClaims.class);
    }

    private String sign(String domain, Object claims) {
        try {
            String payload = encoder.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signature = encoder.encodeToString(hmac(domain, payload));
            return payload + "." + signature;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize recommendation token claims.", e);
        }
    }

    private <T> T verify(String domain, String token, Class<T> type) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new TokenValidationException();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new TokenValidationException();
        }
        try {
            byte[] supplied = decodeCanonical(parts[1]);
            byte[] expected = hmac(domain, parts[0]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new TokenValidationException();
            }
            return objectMapper.readValue(decodeCanonical(parts[0]), type);
        } catch (TokenValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenValidationException();
        }
    }

    private byte[] decodeCanonical(String value) {
        byte[] decoded = decoder.decode(value);
        if (!encoder.encodeToString(decoded).equals(value)) {
            throw new TokenValidationException();
        }
        return decoded;
    }

    private byte[] hmac(String domain, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal((domain + "\0" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign recommendation token.", e);
        }
    }

    public record CursorClaims(
            String actorKey,
            String kind,
            String serveSessionId,
            int offset,
            long expiresAtMs,
            String queryHash
    ) {
    }

    public record ExposureClaims(
            String actorKey,
            String kind,
            String entityId,
            String serveSessionId,
            int rank,
            long expiresAtMs
    ) {
    }

    public static final class TokenValidationException extends RuntimeException {
        private TokenValidationException() {
            super("Recommendation token verification failed.");
        }
    }
}
