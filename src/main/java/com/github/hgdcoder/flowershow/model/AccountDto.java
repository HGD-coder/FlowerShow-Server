package com.github.hgdcoder.flowershow.model;

public record AccountDto(
        String id,
        String userId,
        String nickname,
        String username,
        String role,
        String status,
        boolean generated,
        String createdAt
) {
}
