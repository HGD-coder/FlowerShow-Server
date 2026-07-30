package com.github.hgdcoder.flowershow.persistence.mapper.content;

public record ImportedMediaAssetCommand(
        String contentId,
        String kind,
        String url,
        String storageKey,
        String quality,
        int sortOrder,
        String deliveryType,
        String containerFormat
) {
}
