package com.github.hgdcoder.flowershow.model;

public record AlbumSlideDto(
        int type,
        String mediaUrl
) {
    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_VIDEO = 1;
}