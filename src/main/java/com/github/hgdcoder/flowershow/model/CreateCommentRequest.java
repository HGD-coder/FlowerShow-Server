package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.NotBlank;

public record CreateCommentRequest(
        @NotBlank String userId,
        String parentId,
        @NotBlank String body
) {
}