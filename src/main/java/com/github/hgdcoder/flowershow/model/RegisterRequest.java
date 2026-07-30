package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "username may only contain letters, numbers, dot, underscore, or hyphen")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 80) String nickname,
        String avatarUrl,
        @Size(max = 255) String bio
) {
}
