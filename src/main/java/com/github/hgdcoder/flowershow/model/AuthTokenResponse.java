package com.github.hgdcoder.flowershow.model;

public record AuthTokenResponse(
        String tokenType,
        String accessToken,
        long accessTokenExpiresInSeconds,
        String refreshToken,
        long refreshTokenExpiresInSeconds,
        AuthUserDto user
) {
}
