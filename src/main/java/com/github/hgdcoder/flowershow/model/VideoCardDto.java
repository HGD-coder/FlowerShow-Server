package com.github.hgdcoder.flowershow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoCardDto(
        String type,
        String id,
        String title,
        String author,
        String avatarUrl,
        String videoUrl,
        String coverUrl,
        String musicUrl,
        int likes,
        int comments,
        int collections,
        int shares,
        List<String> tags,
        List<String> recommendWords,
        String userId,
        String creatorSecUid,
        String location,
        String sourceUrl,
        long publishTime,
        Map<String, String> qualityUrls,
        String hlsUrl,
        String authorUserId,
        boolean likedByViewer,
        boolean favoritedByViewer
) implements CardItemDto {
}
