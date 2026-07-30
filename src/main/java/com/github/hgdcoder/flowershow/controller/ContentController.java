package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CommentDto;
import com.github.hgdcoder.flowershow.model.CreateCommentRequest;
import com.github.hgdcoder.flowershow.model.CreateContentRequest;
import com.github.hgdcoder.flowershow.model.InteractionResultDto;
import com.github.hgdcoder.flowershow.model.UploadResponse;
import com.github.hgdcoder.flowershow.model.UserActionRequest;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.CommentService;
import com.github.hgdcoder.flowershow.service.ContentService;
import com.github.hgdcoder.flowershow.service.InteractionService;
import com.github.hgdcoder.flowershow.service.UploadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;
    private final CommentService commentService;
    private final InteractionService interactionService;
    private final UploadService uploadService;

    public ContentController(
            ContentService contentService,
            CommentService commentService,
            InteractionService interactionService,
            UploadService uploadService
    ) {
        this.contentService = contentService;
        this.commentService = commentService;
        this.interactionService = interactionService;
        this.uploadService = uploadService;
    }

    @PostMapping("/contents")
    public CardItemDto createContent(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateContentRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, request.authorUserId());
        return contentService.createContent(request);
    }

    @PostMapping(path = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        return uploadService.store(file);
    }

    @GetMapping("/contents/{contentId}/comments")
    public List<CommentDto> comments(@PathVariable String contentId) {
        return commentService.findComments(contentId);
    }

    @PostMapping("/contents/{contentId}/comments")
    public CommentDto createComment(
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, request.userId());
        return commentService.createComment(contentId, request);
    }

    @PostMapping("/contents/{contentId}/like")
    public InteractionResultDto likeContent(
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserActionRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, request.userId());
        return interactionService.likeContent(contentId, request.userId());
    }

    @DeleteMapping("/contents/{contentId}/like")
    public InteractionResultDto unlikeContent(
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return interactionService.unlikeContent(contentId, AuthenticatedUser.userId(jwt));
    }

    @PostMapping("/contents/{contentId}/favorite")
    public InteractionResultDto favoriteContent(
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserActionRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, request.userId());
        return interactionService.favoriteContent(contentId, request.userId());
    }

    @DeleteMapping("/contents/{contentId}/favorite")
    public InteractionResultDto unfavoriteContent(
            @PathVariable String contentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return interactionService.unfavoriteContent(contentId, AuthenticatedUser.userId(jwt));
    }

    @PostMapping("/comments/{commentId}/like")
    public Map<String, Object> likeComment(
            @PathVariable String commentId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserActionRequest request
    ) {
        AuthenticatedUser.requireUser(jwt, request.userId());
        return Map.of("changed", interactionService.likeComment(commentId, request.userId()));
    }
}
