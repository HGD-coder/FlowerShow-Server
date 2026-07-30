package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ViewerStateRow(
        String contentId,
        boolean likedByViewer,
        boolean favoritedByViewer
) {
}
