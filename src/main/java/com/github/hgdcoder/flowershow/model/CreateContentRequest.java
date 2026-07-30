package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateContentRequest(
        @NotBlank String type,
        @NotBlank String authorUserId,
        @NotBlank String title,
        String description,
        String coverUrl,
        String visibility,
        List<String> mediaUrls,
        List<String> tags,
        List<String> recommendWords
) {
}