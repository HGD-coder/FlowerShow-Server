package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.PageResponse;
import com.github.hgdcoder.flowershow.model.ProfileContentDisplayDto;
import com.github.hgdcoder.flowershow.model.ProfileContentTab;
import com.github.hgdcoder.flowershow.model.UpdateProfileDisplayRequest;
import com.github.hgdcoder.flowershow.model.UpdateUserProfileRequest;
import com.github.hgdcoder.flowershow.model.UserProfileDto;
import com.github.hgdcoder.flowershow.persistence.mapper.social.ProfileMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.ProfileMapper.ContentDisplayRow;
import com.github.hgdcoder.flowershow.persistence.mapper.social.ProfileMapper.ProfileRow;
import com.github.hgdcoder.flowershow.repository.ContentRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class ProfileService {

    private final ProfileMapper profileMapper;
    private final ContentRepository contentRepository;

    public ProfileService(ProfileMapper profileMapper, ContentRepository contentRepository) {
        this.profileMapper = profileMapper;
        this.contentRepository = contentRepository;
    }

    public UserProfileDto findProfile(String userId, String viewerUserId) {
        ProfileRow user = findProfileRow(userId);
        String viewer = normalizeViewer(viewerUserId);
        boolean ownProfile = userId.equals(viewer);
        boolean profileContentVisible = ownProfile || "public".equals(user.profileVisibility());
        boolean following = viewer != null && follows(viewer, userId);
        boolean followedBy = viewer != null && follows(userId, viewer);
        boolean likedTabVisible = profileContentVisible && (ownProfile || user.showLikedOnProfile());
        boolean favoritesTabVisible = profileContentVisible && (ownProfile || user.showFavoritesOnProfile());

        long postCount = profileContentVisible
                ? contentRepository.countProfileContent(userId, ProfileContentTab.POSTS, ownProfile)
                : 0;
        long receivedLikeCount = profileContentVisible ? receivedLikeCount(userId, ownProfile) : 0;
        Long likedContentCount = likedTabVisible
                ? contentRepository.countProfileContent(userId, ProfileContentTab.LIKED, ownProfile)
                : null;
        Long favoriteContentCount = favoritesTabVisible
                ? contentRepository.countProfileContent(userId, ProfileContentTab.FAVORITES, ownProfile)
                : null;

        return new UserProfileDto(
                user.id(),
                user.handle(),
                user.nickname(),
                user.avatarUrl(),
                user.profileBannerUrl(),
                user.bio(),
                user.location(),
                user.source(),
                user.profileVisibility(),
                ownProfile,
                profileContentVisible,
                following,
                followedBy,
                following && followedBy,
                postCount,
                receivedLikeCount,
                followingCount(userId),
                followerCount(userId),
                likedContentCount,
                favoriteContentCount,
                likedTabVisible,
                favoritesTabVisible,
                user.showLikedOnProfile(),
                user.showFavoritesOnProfile(),
                UserService.toIso(user.createdAt()),
                UserService.toIso(user.updatedAt())
        );
    }

    public PageResponse<CardItemDto> findProfileContent(
            String userId,
            String viewerUserId,
            String tabValue,
            int page,
            int pageSize
    ) {
        ProfileRow user = findProfileRow(userId);
        String viewer = normalizeViewer(viewerUserId);
        boolean ownerView = userId.equals(viewer);
        ProfileContentTab tab = parseTab(tabValue);
        assertTabVisible(user, ownerView, tab);

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        long offsetValue = (long) (safePage - 1) * safePageSize;
        int offset = (int) Math.min(Integer.MAX_VALUE, offsetValue);
        long total = contentRepository.countProfileContent(userId, tab, ownerView);
        List<CardItemDto> items = contentRepository.findProfileContent(
                userId,
                tab,
                ownerView,
                viewer,
                offset,
                safePageSize
        );
        return PageResponse.of(items, safePage, safePageSize, total);
    }

    @Transactional
    public UserProfileDto updateProfile(
            String userId,
            String actorUserId,
            UpdateUserProfileRequest request
    ) {
        requireOwner(userId, actorUserId);
        ProfileRow current = findProfileRow(userId);

        String handle = request.handle() == null ? current.handle() : requiredTrimmed(request.handle(), "handle");
        String nickname = request.nickname() == null
                ? current.nickname()
                : requiredTrimmed(request.nickname(), "nickname");
        String profileVisibility = request.profileVisibility() == null
                ? current.profileVisibility()
                : request.profileVisibility().trim().toLowerCase(Locale.ROOT);
        if (!List.of("public", "private").contains(profileVisibility)) {
            throw new ResponseStatusException(BAD_REQUEST, "profileVisibility must be public or private.");
        }

        try {
            profileMapper.updateProfile(
                    userId,
                    handle,
                    nickname,
                    optionalText(request.avatarUrl(), current.avatarUrl()),
                    optionalText(request.profileBannerUrl(), current.profileBannerUrl()),
                    optionalText(request.bio(), current.bio()),
                    optionalText(request.location(), current.location()),
                    profileVisibility,
                    request.showLikedOnProfile() == null
                            ? current.showLikedOnProfile()
                            : request.showLikedOnProfile(),
                    request.showFavoritesOnProfile() == null
                            ? current.showFavoritesOnProfile()
                            : request.showFavoritesOnProfile()
            );
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(CONFLICT, "handle is already in use.", e);
        }
        return findProfile(userId, userId);
    }

    @Transactional
    public ProfileContentDisplayDto updateContentDisplay(
            String userId,
            String contentId,
            String actorUserId,
            UpdateProfileDisplayRequest request
    ) {
        requireOwner(userId, actorUserId);
        if (request.showOnProfile() == null && request.pinned() == null && request.sortOrder() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one profile display field is required.");
        }

        ContentDisplayRow current = findContentDisplay(contentId);
        if (!userId.equals(current.authorUserId())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the content owner can change profile display.");
        }

        boolean showOnProfile = request.showOnProfile() == null
                ? current.showOnProfile()
                : request.showOnProfile();
        Timestamp pinnedAt = request.pinned() == null
                ? current.pinnedAt()
                : request.pinned() ? Timestamp.from(Instant.now()) : null;
        int sortOrder = request.sortOrder() == null ? current.sortOrder() : request.sortOrder();

        profileMapper.updateContentDisplay(contentId, showOnProfile, pinnedAt, sortOrder);
        return new ProfileContentDisplayDto(contentId, showOnProfile, pinnedAt != null, sortOrder);
    }

    private void assertTabVisible(ProfileRow user, boolean ownerView, ProfileContentTab tab) {
        if (!ownerView && !"public".equals(user.profileVisibility())) {
            throw new ResponseStatusException(FORBIDDEN, "This profile is private.");
        }
        if (!ownerView && tab == ProfileContentTab.LIKED && !user.showLikedOnProfile()) {
            throw new ResponseStatusException(FORBIDDEN, "This user's liked content is private.");
        }
        if (!ownerView && tab == ProfileContentTab.FAVORITES && !user.showFavoritesOnProfile()) {
            throw new ResponseStatusException(FORBIDDEN, "This user's favorites are private.");
        }
    }

    private ProfileContentTab parseTab(String value) {
        String normalized = value == null ? "posts" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "posts" -> ProfileContentTab.POSTS;
            case "liked" -> ProfileContentTab.LIKED;
            case "favorites" -> ProfileContentTab.FAVORITES;
            default -> throw new ResponseStatusException(
                    BAD_REQUEST,
                    "tab must be posts, liked, or favorites."
            );
        };
    }

    private ProfileRow findProfileRow(String userId) {
        ProfileRow row = profileMapper.findProfileById(userId);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
        return row;
    }

    private ContentDisplayRow findContentDisplay(String contentId) {
        ContentDisplayRow row = profileMapper.findContentDisplayById(contentId);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "Content not found: " + contentId);
        }
        return row;
    }

    private String normalizeViewer(String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return null;
        }
        String viewer = viewerUserId.trim();
        findProfileRow(viewer);
        return viewer;
    }

    private void requireOwner(String userId, String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Authentication is required.");
        }
        String actor = actorUserId.trim();
        if (!userId.equals(actor)) {
            throw new ResponseStatusException(FORBIDDEN, "Authenticated user does not match the profile owner.");
        }
        findProfileRow(userId);
    }

    private boolean follows(String followerId, String followingId) {
        return profileMapper.countFollow(followerId, followingId) > 0;
    }

    private long receivedLikeCount(String userId, boolean ownerView) {
        return ownerView
                ? profileMapper.countReceivedLikesForOwner(userId)
                : profileMapper.countReceivedLikesForPublicProfile(userId);
    }

    private long followingCount(String userId) {
        Long value = profileMapper.findFollowingCount(userId);
        return value == null ? 0 : value;
    }

    private long followerCount(String userId) {
        Long value = profileMapper.findFollowerCount(userId);
        return value == null ? 0 : value;
    }

    private static String requiredTrimmed(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, field + " cannot be blank.");
        }
        return trimmed;
    }

    private static String optionalText(String update, String current) {
        if (update == null) {
            return current;
        }
        String trimmed = update.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
