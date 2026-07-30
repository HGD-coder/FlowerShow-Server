package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.NotBlank;

public record MediaCrawlerImportRequest(
        @NotBlank String path,
        String videoBaseUrl,
        String assetBaseUrl,
        Boolean replaceMediaAssets
) {
    public MediaCrawlerImportRequest(String path, String videoBaseUrl, Boolean replaceMediaAssets) {
        this(path, videoBaseUrl, null, replaceMediaAssets);
    }

    public boolean shouldReplaceMediaAssets() {
        return replaceMediaAssets == null || replaceMediaAssets;
    }

    public String effectiveAssetBaseUrl() {
        return assetBaseUrl == null || assetBaseUrl.isBlank() ? videoBaseUrl : assetBaseUrl;
    }
}
