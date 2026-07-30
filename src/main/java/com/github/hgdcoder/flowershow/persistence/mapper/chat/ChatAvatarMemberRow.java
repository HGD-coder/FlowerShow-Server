package com.github.hgdcoder.flowershow.persistence.mapper.chat;

public record ChatAvatarMemberRow(
        String conversationId,
        String userId,
        String nickname,
        String avatarUrl
) {
}
