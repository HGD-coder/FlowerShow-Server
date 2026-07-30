package com.github.hgdcoder.flowershow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageCardDto(
        String type,
        String id,
        String title,
        String author,
        String imageUrl,
        int likes,
        int comments,
        String authorUserId,
        boolean likedByViewer,
        boolean favoritedByViewer
) implements CardItemDto {
}
