package com.github.hgdcoder.flowershow.model;

public record AuthUserDto(
        String userId,
        String accountId,
        String username,
        String nickname,
        String avatarUrl,
        String role
) {
}
