package com.github.hgdcoder.flowershow.model;

public sealed interface CardItemDto permits VideoCardDto, ImageCardDto, AlbumCardDto {

    String type();

    String id();

    String title();

    String author();

    String authorUserId();

    boolean likedByViewer();

    boolean favoritedByViewer();
}
