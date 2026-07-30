package com.github.hgdcoder.flowershow.model;

public record UserDto(
        String id,
        String nickname,
        String avatarUrl,
        String bio,
        String location,
        String source,
        String createdAt
) {
}