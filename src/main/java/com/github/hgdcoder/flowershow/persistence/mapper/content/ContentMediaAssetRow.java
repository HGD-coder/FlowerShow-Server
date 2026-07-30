package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ContentMediaAssetRow(
        String kind,
        String quality,
        String url,
        String storageKey
) {
}
