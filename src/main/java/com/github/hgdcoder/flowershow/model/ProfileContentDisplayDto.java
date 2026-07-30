package com.github.hgdcoder.flowershow.model;

public record ProfileContentDisplayDto(
        String contentId,
        boolean showOnProfile,
        boolean pinned,
        int sortOrder
) {
}
