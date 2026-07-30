package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record NewContentMediaAsset(
        String contentId,
        String kind,
        String url,
        int sortOrder
) {
}
