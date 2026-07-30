package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ImportedUserCommand(
        String id,
        String nickname,
        String avatarUrl,
        String bio,
        String location,
        String sourceUserId
) {
}
