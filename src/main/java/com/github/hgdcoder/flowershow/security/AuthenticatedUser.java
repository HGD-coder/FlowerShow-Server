package com.github.hgdcoder.flowershow.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static String userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required.");
        }
        return jwt.getSubject();
    }

    public static String optionalUserId(Jwt jwt) {
        return jwt == null ? null : userId(jwt);
    }

    public static String requireUser(Jwt jwt, String expectedUserId) {
        String authenticatedUserId = userId(jwt);
        if (!authenticatedUserId.equals(expectedUserId)) {
            throw new ResponseStatusException(FORBIDDEN, "You cannot act as another user.");
        }
        return authenticatedUserId;
    }
}
