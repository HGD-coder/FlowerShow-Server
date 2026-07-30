package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.Min;

public record UpdateProfileDisplayRequest(
        Boolean showOnProfile,
        Boolean pinned,
        @Min(0) Integer sortOrder
) {
}
