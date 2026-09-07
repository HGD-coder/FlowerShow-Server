package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthMapper {

    long countAccountsByUsername(@Param("username") String username);

    long countUsersByNormalizedHandle(@Param("handle") String handle);

    int insertUser(
            @Param("id") String id,
            @Param("handle") String handle,
            @Param("nickname") String nickname,
            @Param("avatarUrl") String avatarUrl,
            @Param("bio") String bio
    );

    int insertAccount(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash
    );

    int insertAccountWithRole(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("role") String role
    );

    int insertUserSocialStats(@Param("userId") String userId);

    int insertNotificationUnreadStats(@Param("userId") String userId);

    AccountRow findAccountByUsername(@Param("username") String username);

    AccountRow findAccountByUserId(@Param("userId") String userId);

    int updateLoginSuccess(
            @Param("accountId") String accountId,
            @Param("now") Timestamp now
    );

    int updateLoginFailure(
            @Param("accountId") String accountId,
            @Param("maxFailedAttempts") int maxFailedAttempts,
            @Param("lockedUntil") Timestamp lockedUntil,
            @Param("now") Timestamp now
    );

    int insertRefreshToken(
            @Param("id") String id,
            @Param("accountId") String accountId,
            @Param("tokenHash") String tokenHash,
            @Param("expiresAt") Timestamp expiresAt
    );

    RefreshTokenRow findRefreshToken(@Param("tokenHash") String tokenHash);

    int rotateRefreshToken(
            @Param("id") String id,
            @Param("replacementId") String replacementId,
            @Param("revokedAt") Timestamp revokedAt
    );

    int revokeRefreshToken(
            @Param("tokenHash") String tokenHash,
            @Param("userId") String userId
    );

    int revokeAllRefreshTokens(@Param("userId") String userId);

    record AccountRow(
            String id,
            String userId,
            String username,
            String passwordHash,
            String role,
            String status,
            int failedLoginAttempts,
            Timestamp lockedUntil,
            String nickname,
            String avatarUrl
    ) {
    }

    record RefreshTokenRow(
            String refreshId,
            Timestamp expiresAt,
            Timestamp revokedAt,
            String accountId,
            String userId,
            String username,
            String passwordHash,
            String role,
            String status,
            int failedLoginAttempts,
            Timestamp lockedUntil,
            String nickname,
            String avatarUrl
    ) {
    }
}
