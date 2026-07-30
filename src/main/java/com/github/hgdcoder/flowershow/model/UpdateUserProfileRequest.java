package com.github.hgdcoder.flowershow.model;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @Size(max = 64)
        @Pattern(regexp = "[\\p{L}\\p{N}._-]+", message = "handle may only contain letters, numbers, dot, underscore, or hyphen")
        String handle,
        @Size(max = 80) String nickname,
        String avatarUrl,
        String profileBannerUrl,
        @Size(max = 255) String bio,
        @Size(max = 80) String location,
        @Pattern(regexp = "public|private", message = "profileVisibility must be public or private")
        String profileVisibility,
        Boolean showLikedOnProfile,
        Boolean showFavoritesOnProfile
) {
}
