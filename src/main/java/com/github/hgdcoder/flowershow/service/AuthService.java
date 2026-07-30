package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.config.JwtProperties;
import com.github.hgdcoder.flowershow.model.AuthTokenResponse;
import com.github.hgdcoder.flowershow.model.AuthUserDto;
import com.github.hgdcoder.flowershow.model.LoginRequest;
import com.github.hgdcoder.flowershow.model.RegisterRequest;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthMapper.AccountRow;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthMapper.RefreshTokenRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final String dummyPasswordHash;

    public AuthService(
            AuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthTokenResponse register(RegisterRequest request) {
        validateRegistrationPassword(request.password());
        String username = normalizeUsername(request.username());
        if (usernameExists(username) || handleExists(username)) {
            throw new ResponseStatusException(CONFLICT, "Username is already in use.");
        }

        String userId = "usr_" + compactUuid();
        String accountId = "acc_" + compactUuid();
        String nickname = request.nickname().trim();
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            authMapper.insertUser(
                    userId,
                    username,
                    nickname,
                    optionalText(request.avatarUrl()),
                    optionalText(request.bio())
            );
            authMapper.insertAccount(accountId, userId, username, passwordHash);
            authMapper.insertUserSocialStats(userId);
            authMapper.insertNotificationUnreadStats(userId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(CONFLICT, "Username is already in use.", e);
        }

        AccountRow account = new AccountRow(
                accountId,
                userId,
                username,
                passwordHash,
                "user",
                "active",
                0,
                null,
                nickname,
                optionalText(request.avatarUrl())
        );
        return issueTokenPair(account);
    }

    public AuthTokenResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        String username = normalizeUsername(request.username());
        AccountRow account = findAccountByUsername(username);
        if (account == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw invalidCredentials();
        }
        if (!"active".equals(account.status())) {
            throw new ResponseStatusException(FORBIDDEN, "Account is not active.");
        }

        Instant now = Instant.now();
        if (account.lockedUntil() != null && account.lockedUntil().toInstant().isAfter(now)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Too many login attempts. Try again later.");
        }
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            recordLoginFailure(account, now);
            throw invalidCredentials();
        }

        authMapper.updateLoginSuccess(account.id(), Timestamp.from(now));
        return issueTokenPair(account);
    }

    @Transactional
    public AuthTokenResponse refresh(String rawRefreshToken) {
        RefreshTokenRow current = findRefreshToken(hashToken(rawRefreshToken));
        Instant now = Instant.now();
        if (current == null || current.revokedAt() != null || !current.expiresAt().toInstant().isAfter(now)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token is invalid or expired.");
        }
        AccountRow account = toAccountRow(current);
        if (!"active".equals(account.status())) {
            throw new ResponseStatusException(FORBIDDEN, "Account is not active.");
        }

        GeneratedRefreshToken replacement = newRefreshToken(account.id(), now);
        int changed = authMapper.rotateRefreshToken(
                current.refreshId(),
                replacement.id(),
                Timestamp.from(now)
        );
        if (changed == 0) {
            throw new ResponseStatusException(UNAUTHORIZED, "Refresh token has already been used.");
        }
        return tokenResponse(account, replacement.rawToken(), now);
    }

    @Transactional
    public boolean logout(String userId, String rawRefreshToken) {
        return authMapper.revokeRefreshToken(hashToken(rawRefreshToken), userId) > 0;
    }

    @Transactional
    public int logoutAll(String userId) {
        return authMapper.revokeAllRefreshTokens(userId);
    }

    public AuthUserDto currentUser(String userId) {
        AccountRow account = findAccountByUserId(userId);
        if (account == null || !"active".equals(account.status())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Account is not active.");
        }
        return toUser(account);
    }

    private AuthTokenResponse issueTokenPair(AccountRow account) {
        Instant now = Instant.now();
        GeneratedRefreshToken refreshToken = newRefreshToken(account.id(), now);
        return tokenResponse(account, refreshToken.rawToken(), now);
    }

    private AuthTokenResponse tokenResponse(AccountRow account, String refreshToken, Instant now) {
        Instant accessExpiresAt = now.plus(jwtProperties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(now)
                .expiresAt(accessExpiresAt)
                .subject(account.userId())
                .id(compactUuid())
                .claim("accountId", account.id())
                .claim("username", account.username())
                .claim("roles", List.of(account.role().toUpperCase(Locale.ROOT)))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthTokenResponse(
                "Bearer",
                accessToken,
                jwtProperties.accessTokenTtl().toSeconds(),
                refreshToken,
                jwtProperties.refreshTokenTtl().toSeconds(),
                toUser(account)
        );
    }

    private GeneratedRefreshToken newRefreshToken(String accountId, Instant now) {
        byte[] random = new byte[48];
        SECURE_RANDOM.nextBytes(random);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = "rft_" + compactUuid();
        authMapper.insertRefreshToken(
                id,
                accountId,
                hashToken(rawToken),
                Timestamp.from(now.plus(jwtProperties.refreshTokenTtl()))
        );
        return new GeneratedRefreshToken(id, rawToken);
    }

    private void recordLoginFailure(AccountRow account, Instant now) {
        int failures = account.failedLoginAttempts() + 1;
        Timestamp lockedUntil = failures >= MAX_FAILED_ATTEMPTS
                ? Timestamp.from(now.plus(LOCK_DURATION))
                : null;
        authMapper.updateLoginFailure(
                account.id(),
                failures,
                lockedUntil,
                Timestamp.from(now)
        );
    }

    private AccountRow findAccountByUsername(String username) {
        return authMapper.findAccountByUsername(username);
    }

    private AccountRow findAccountByUserId(String userId) {
        return authMapper.findAccountByUserId(userId);
    }

    private RefreshTokenRow findRefreshToken(String tokenHash) {
        return authMapper.findRefreshToken(tokenHash);
    }

    private boolean usernameExists(String username) {
        return authMapper.countAccountsByUsername(username) > 0;
    }

    private boolean handleExists(String handle) {
        return authMapper.countUsersByNormalizedHandle(handle) > 0;
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid username or password.");
    }

    private static void validateRegistrationPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes.");
        }
    }

    private static AuthUserDto toUser(AccountRow account) {
        return new AuthUserDto(
                account.userId(),
                account.id(),
                account.username(),
                account.nickname(),
                account.avatarUrl(),
                account.role()
        );
    }

    private static AccountRow toAccountRow(RefreshTokenRow token) {
        return new AccountRow(
                token.accountId(),
                token.userId(),
                token.username(),
                token.passwordHash(),
                token.role(),
                token.status(),
                token.failedLoginAttempts(),
                token.lockedUntil(),
                token.nickname(),
                token.avatarUrl()
        );
    }

    private record GeneratedRefreshToken(String id, String rawToken) {
    }
}
