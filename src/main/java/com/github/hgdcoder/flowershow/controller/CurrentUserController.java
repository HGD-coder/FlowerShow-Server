package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.model.DeviceTokenDto;
import com.github.hgdcoder.flowershow.model.DeviceTokenRequest;
import com.github.hgdcoder.flowershow.model.NotificationDto;
import com.github.hgdcoder.flowershow.model.PageResponse;
import com.github.hgdcoder.flowershow.model.UpdateUserProfileRequest;
import com.github.hgdcoder.flowershow.model.UserProfileDto;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.DeviceTokenService;
import com.github.hgdcoder.flowershow.service.NotificationService;
import com.github.hgdcoder.flowershow.service.ProfileService;
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
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final ProfileService profileService;
    private final NotificationService notificationService;
    private final DeviceTokenService deviceTokenService;

    public CurrentUserController(
            ProfileService profileService,
            NotificationService notificationService,
            DeviceTokenService deviceTokenService
    ) {
        this.profileService = profileService;
        this.notificationService = notificationService;
        this.deviceTokenService = deviceTokenService;
    }

    @GetMapping
    public UserProfileDto profile(@AuthenticationPrincipal Jwt jwt) {
        String userId = AuthenticatedUser.userId(jwt);
        return profileService.findProfile(userId, userId);
    }

    @PatchMapping
    public UserProfileDto updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        String userId = AuthenticatedUser.userId(jwt);
        return profileService.updateProfile(userId, userId, request);
    }

    @GetMapping("/contents")
    public PageResponse<CardItemDto> content(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "posts") String tab,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        String userId = AuthenticatedUser.userId(jwt);
        return profileService.findProfileContent(userId, userId, tab, page, pageSize);
    }

    @GetMapping("/notifications")
    public CursorPageResponse<NotificationDto> notifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return notificationService.page(AuthenticatedUser.userId(jwt), cursor, pageSize);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("unreadCount", notificationService.unreadCount(AuthenticatedUser.userId(jwt)));
    }

    @PostMapping("/notifications/{notificationId}/read")
    public Map<String, Object> markNotificationRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String notificationId
    ) {
        String userId = AuthenticatedUser.userId(jwt);
        return Map.of(
                "changed", notificationService.markRead(userId, notificationId),
                "unreadCount", notificationService.unreadCount(userId)
        );
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllNotificationsRead(@AuthenticationPrincipal Jwt jwt) {
        String userId = AuthenticatedUser.userId(jwt);
        int changedCount = notificationService.markAllRead(userId);
        // Read the counter back instead of assuming 0: a notification can be
        // inserted concurrently with this call and remain unread.
        return Map.of(
                "changedCount", changedCount,
                "unreadCount", notificationService.unreadCount(userId)
        );
    }

    @GetMapping("/devices")
    public List<DeviceTokenDto> devices(@AuthenticationPrincipal Jwt jwt) {
        return deviceTokenService.list(AuthenticatedUser.userId(jwt));
    }

    @PostMapping("/devices")
    public DeviceTokenDto registerDevice(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DeviceTokenRequest request
    ) {
        return deviceTokenService.register(AuthenticatedUser.userId(jwt), request);
    }

    @DeleteMapping("/devices/{deviceId}")
    public Map<String, Boolean> disableDevice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String deviceId
    ) {
        return Map.of("changed", deviceTokenService.disable(AuthenticatedUser.userId(jwt), deviceId));
    }
}
