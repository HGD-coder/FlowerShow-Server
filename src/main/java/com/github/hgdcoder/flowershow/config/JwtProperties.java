package com.github.hgdcoder.flowershow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flower-show.auth.jwt")
public record JwtProperties(
        String secret,
        String secretFile,
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
