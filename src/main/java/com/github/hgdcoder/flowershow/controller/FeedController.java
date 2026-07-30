package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.ContentService;
import com.github.hgdcoder.flowershow.service.FollowingFeedService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class FeedController {

    private final ContentService contentService;
    private final FollowingFeedService followingFeedService;

    public FeedController(ContentService contentService, FollowingFeedService followingFeedService) {
        this.contentService = contentService;
        this.followingFeedService = followingFeedService;
    }

    @GetMapping("/feed")
    public List<CardItemDto> feed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize
    ) {
        return contentService.feed(page, pageSize, AuthenticatedUser.optionalUserId(jwt));
    }

    @GetMapping("/feed/following")
    public CursorPageResponse<CardItemDto> followingFeed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize
    ) {
        return followingFeedService.page(AuthenticatedUser.userId(jwt), cursor, pageSize);
    }

    @GetMapping("/videos")
    public List<VideoCardDto> videos(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize
    ) {
        return contentService.videos(page, pageSize, AuthenticatedUser.optionalUserId(jwt));
    }

    @GetMapping("/videos/{id}/recommend-words")
    public List<String> recommendWords(@PathVariable String id) {
        return contentService.recommendWords(id);
    }
}
