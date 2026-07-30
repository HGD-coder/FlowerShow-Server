package com.github.hgdcoder.flowershow.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.CursorClaims;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.ExposureClaims;
import com.github.hgdcoder.flowershow.recommendation.RecommendationTokenService.TokenValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecommendationTokenServiceTest {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String EXPOSURE_DOMAIN = "flowershow/recommendation/exposure/v1";
    private static final String BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final byte[] SECRET =
            "recommendation-token-test-secret".getBytes(StandardCharsets.UTF_8);

    private RecommendationTokenService tokens;

    @BeforeEach
    void setUp() {
        tokens = new RecommendationTokenService(
                new SecretKeySpec(SECRET, HMAC_ALGORITHM),
                new ObjectMapper()
        );
    }

    @Test
    void canonicalCursorAndExposureTokensRoundTrip() {
        CursorClaims cursor = new CursorClaims(
                "actor-key",
                "feed",
                "serve-session",
                25,
                1_900_000_000_000L,
                "query-hash"
        );
        ExposureClaims exposure = new ExposureClaims(
                "actor-key",
                "feed",
                "video-42",
                "serve-session",
                3,
                1_900_000_000_000L
        );

        assertEquals(cursor, tokens.verifyCursor(tokens.signCursor(cursor)));
        assertEquals(exposure, tokens.verifyExposure(tokens.signExposure(exposure)));
    }

    @Test
    void equivalentNonCanonicalSignatureIsRejected() {
        String canonicalToken = tokens.signCursor(new CursorClaims(
                "actor-key",
                "feed",
                "serve-session",
                25,
                1_900_000_000_000L,
                "query-hash"
        ));
        String[] parts = canonicalToken.split("\\.", -1);
        String nonCanonicalSignature = equivalentNonCanonicalEncoding(parts[1]);

        assertNotEquals(parts[1], nonCanonicalSignature);
        assertArrayEquals(DECODER.decode(parts[1]), DECODER.decode(nonCanonicalSignature));
        assertCursorValidationFailure(parts[0] + "." + nonCanonicalSignature);

        String paddedSignature = addPadding(parts[1]);
        assertArrayEquals(DECODER.decode(parts[1]), DECODER.decode(paddedSignature));
        assertCursorValidationFailure(parts[0] + "." + paddedSignature);
    }

    @Test
    void equivalentNonCanonicalPayloadIsRejectedEvenWithMatchingSignature() throws Exception {
        String canonicalToken = tokens.signExposure(new ExposureClaims(
                "actor",
                "feed",
                "video-1",
                "session",
                1,
                123_456_789L
        ));
        String[] parts = canonicalToken.split("\\.", -1);
        String nonCanonicalPayload = equivalentNonCanonicalEncoding(parts[0]);

        assertNotEquals(parts[0], nonCanonicalPayload);
        assertArrayEquals(DECODER.decode(parts[0]), DECODER.decode(nonCanonicalPayload));
        assertExposureValidationFailure(sign(EXPOSURE_DOMAIN, nonCanonicalPayload));

        String paddedPayload = addPadding(parts[0]);
        assertArrayEquals(DECODER.decode(parts[0]), DECODER.decode(paddedPayload));
        assertExposureValidationFailure(sign(EXPOSURE_DOMAIN, paddedPayload));
    }

    private void assertCursorValidationFailure(String token) {
        TokenValidationException exception =
                assertThrows(TokenValidationException.class, () -> tokens.verifyCursor(token));
        assertEquals("Recommendation token verification failed.", exception.getMessage());
    }

    private void assertExposureValidationFailure(String token) {
        TokenValidationException exception =
                assertThrows(TokenValidationException.class, () -> tokens.verifyExposure(token));
        assertEquals("Recommendation token verification failed.", exception.getMessage());
    }

    private static String equivalentNonCanonicalEncoding(String canonical) {
        byte[] decoded = DECODER.decode(canonical);
        int lastIndex = canonical.length() - 1;
        for (int i = 0; i < BASE64URL_ALPHABET.length(); i++) {
            char replacement = BASE64URL_ALPHABET.charAt(i);
            if (replacement == canonical.charAt(lastIndex)) {
                continue;
            }
            String candidate = canonical.substring(0, lastIndex) + replacement;
            if (Arrays.equals(decoded, DECODER.decode(candidate))) {
                return candidate;
            }
        }
        throw new AssertionError("Test value does not have an equivalent non-canonical Base64URL encoding.");
    }

    private static String addPadding(String canonical) {
        int paddingLength = (4 - canonical.length() % 4) % 4;
        if (paddingLength == 0) {
            throw new AssertionError("Test value does not require Base64URL padding.");
        }
        return canonical + "=".repeat(paddingLength);
    }

    private static String sign(String domain, String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(SECRET, HMAC_ALGORITHM));
        byte[] signature = mac.doFinal(
                (domain + "\0" + payload).getBytes(StandardCharsets.UTF_8)
        );
        return payload + "." + ENCODER.encodeToString(signature);
    }
}
