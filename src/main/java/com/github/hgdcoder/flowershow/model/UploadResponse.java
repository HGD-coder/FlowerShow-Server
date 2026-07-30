package com.github.hgdcoder.flowershow.model;

public record UploadResponse(
        String fileName,
        String url,
        String storageKey,
        String contentType,
        long size
) {
}