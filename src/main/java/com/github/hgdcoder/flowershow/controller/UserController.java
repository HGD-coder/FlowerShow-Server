package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.FollowActionResultDto;
import com.github.hgdcoder.flowershow.model.FollowPreferencesDto;
import com.github.hgdcoder.flowershow.model.FollowPreferencesRequest;
import com.github.hgdcoder.flowershow.model.NotificationDto;
import com.github.hgdcoder.flowershow.model.PageResponse;
import com.github.hgdcoder.flowershow.model.ProfileContentDisplayDto;
import com.github.hgdcoder.flowershow.model.UpdateProfileDisplayRequest;
import com.github.hgdcoder.flowershow.model.UpdateUserProfileRequest;
import com.github.hgdcoder.flowershow.model.UserListItemDto;
import com.github.hgdcoder.flowershow.model.UserDto;
import com.github.hgdcoder.flowershow.model.UserProfileDto;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.ContentService;
import com.github.hgdcoder.flowershow.service.FollowingFeedService;
import com.github.hgdcoder.flowershow.service.NotificationService;
import com.github.hgdcoder.flowershow.service.ProfileService;
import com.github.hgdcoder.flowershow.service.SocialService;
import com.github.hgdcoder.flowershow.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final ContentService contentService;
    private final ProfileService profileService;
    private final SocialService socialService;
    private final NotificationService notificationService;
    private final FollowingFeedService followingFeedService;

    public UserController(
            UserService userService,
            ContentService contentService,
            ProfileService profileService,
            SocialService socialService,
            NotificationService notificationService,
            FollowingFeedService followingFeedService
    ) {
        this.userService = userService;
        this.contentService = contentService;
        this.profileService = profileService;
        this.socialService = socialService;
        this.notificationService = notificationService;
        this.followingFeedService = followingFeedService;
    }

    @GetMapping
    public List<UserDto> users() {
        return userService.findUsers();
    }

    @GetMapping("/{id}")
    public UserDto user(@PathVariable String id) {
        return userService.findUser(id);
    }

    @GetMapping("/{id}/profile")
    public UserProfileDto profile(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return profileService.findProfile(id, AuthenticatedUser.optionalUserId(jwt));
    }

    @PatchMapping("/{id}/profile")
    public UserProfileDto updateProfile(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        return profileService.updateProfile(id, AuthenticatedUser.requireUser(jwt, id), request);
    }

    @GetMapping("/{id}/profile/contents")
    public PageResponse<CardItemDto> profileContent(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "posts") String tab,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return profileService.findProfileContent(id, AuthenticatedUser.optionalUserId(jwt), tab, page, pageSize);
    }

    @PatchMapping("/{id}/contents/{contentId}/profile-display")
    public ProfileContentDisplayDto updateProfileDisplay(
            @PathVariable String id,
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileDisplayRequest request
    ) {
        return profileService.updateContentDisplay(id, contentId, AuthenticatedUser.requireUser(jwt, id), request);
    }

    @GetMapping("/{id}/contents")
    public List<CardItemDto> userContent(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return contentService.userContent(id, AuthenticatedUser.optionalUserId(jwt));
    }

    @GetMapping("/{id}/following-feed")
    public List<CardItemDto> followingFeed(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser.requireUser(jwt, id);
        return followingFeedService.page(id, null, 50).items();
    }

    @PostMapping("/{id}/following/{targetUserId}")
    public FollowActionResultDto follow(
            @PathVariable String id,
            @PathVariable String targetUserId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        AuthenticatedUser.requireUser(jwt, id);
        return socialService.followWithResult(id, targetUserId);
    }

    @DeleteMapping("/{id}/following/{targetUserId}")
    public FollowActionResultDto unfollow(
            @PathVariable String id,
            @PathVariable String targetUserId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        AuthenticatedUser.requireUser(jwt, id);
        return socialService.unfollowWithResult(id, targetUserId);
    }

    @GetMapping("/{id}/following/{targetUserId}/preferences")
    public FollowPreferencesDto followPreferences(
            @PathVariable String id,
            @PathVariable String targetUserId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        AuthenticatedUser.requireUser(jwt, id);
        return socialService.preferences(id, targetUserId);
    }

    @PatchMapping("/{id}/following/{targetUserId}/preferences")
    public FollowPreferencesDto updateFollowPreferences(
            @PathVariable String id,
            @PathVariable String targetUserId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody FollowPreferencesRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, id);
        return socialService.updatePreferences(id, targetUserId, request);
    }

    @GetMapping("/{id}/followers")
    public PageResponse<UserListItemDto> followers(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return socialService.followers(id, AuthenticatedUser.optionalUserId(jwt), keyword, page, pageSize);
    }

    @GetMapping("/{id}/following")
    public PageResponse<UserListItemDto> following(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return socialService.following(id, AuthenticatedUser.optionalUserId(jwt), keyword, page, pageSize);
    }

    @GetMapping("/{id}/notifications")
    public List<NotificationDto> notifications(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUser.requireUser(jwt, id);
        return notificationService.list(id);
    }

    @PostMapping("/{id}/notifications/{notificationId}/read")
    public Map<String, Object> markRead(
            @PathVariable String id,
            @PathVariable String notificationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        AuthenticatedUser.requireUser(jwt, id);
        return Map.of("changed", notificationService.markRead(id, notificationId));
    }
}
