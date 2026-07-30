package com.github.hgdcoder.flowershow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlbumCardDto(
        String type,
        String id,
        String title,
        String author,
        String avatarUrl,
        List<AlbumSlideDto> slides,
        String bgMusicUrl,
        int likes,
        int comments,
        int shares,
        List<String> tags,
        List<String> recommendWords,
        String authorUserId,
        boolean likedByViewer,
        boolean favoritedByViewer
) implements CardItemDto {
}
