package com.github.hgdcoder.flowershow.model;

public record InteractionResultDto(
        boolean changed,
        ContentStatsDto stats
) {
}