package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.NotBlank;

public record UserActionRequest(
        @NotBlank String userId
) {
}