package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.model.FollowActionResultDto;
import com.github.hgdcoder.flowershow.model.FollowPreferencesDto;
import com.github.hgdcoder.flowershow.model.FollowPreferencesRequest;
import com.github.hgdcoder.flowershow.model.PageResponse;
import com.github.hgdcoder.flowershow.model.UserListItemDto;
import com.github.hgdcoder.flowershow.persistence.mapper.social.SocialMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.SocialMapper.FollowPreferencesRow;
import com.github.hgdcoder.flowershow.persistence.mapper.social.SocialMapper.RelationRow;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SocialService {

    private final SocialMapper socialMapper;
    private final EventPublisher eventPublisher;
    private final int followBackfillSize;

    public SocialService(
            SocialMapper socialMapper,
            EventPublisher eventPublisher,
            @Value("${flower-show.feed.follow-backfill-size:100}") int followBackfillSize
    ) {
        this.socialMapper = socialMapper;
        this.eventPublisher = eventPublisher;
        this.followBackfillSize = Math.min(500, Math.max(0, followBackfillSize));
    }

    @Transactional
    public boolean follow(String followerId, String followingId) {
        if (followerId.equals(followingId)) {
            throw new ResponseStatusException(BAD_REQUEST, "Cannot follow yourself.");
        }
        assertUserExists(followerId);
        assertUserExists(followingId);
        lockUser(followerId);
        boolean changed = socialMapper.insertFollowIfAbsent(
                followerId,
                followingId,
                Math.floorMod(followerId.hashCode(), 128)
        ) > 0;
        if (changed) {
            incrementSocialCounts(followerId, followingId);
            backfillFeed(followerId, followingId);
            eventPublisher.publish(DomainEvent.of(
                    EventTypes.USER_FOLLOWED,
                    "follow",
                    followerId + ":" + followingId,
                    Map.of("followerId", followerId, "followingId", followingId)
            ));
        }
        return changed;
    }

    @Transactional
    public boolean unfollow(String followerId, String followingId) {
        assertUserExists(followerId);
        assertUserExists(followingId);
        lockUser(followerId);
        boolean changed = socialMapper.deleteFollow(followerId, followingId) > 0;
        if (changed) {
            decrementSocialCounts(followerId, followingId);
            socialMapper.deleteFeedEntries(followerId, followingId);
        }
        return changed;
    }

    @Transactional
    public FollowActionResultDto followWithResult(String followerId, String followingId) {
        return actionResult(followerId, followingId, follow(followerId, followingId));
    }

    @Transactional
    public FollowActionResultDto unfollowWithResult(String followerId, String followingId) {
        return actionResult(followerId, followingId, unfollow(followerId, followingId));
    }

    public FollowPreferencesDto preferences(String followerId, String followingId) {
        assertUserExists(followerId);
        assertUserExists(followingId);
        FollowPreferencesRow row = socialMapper.findPreferences(followerId, followingId);
        return row == null
                ? new FollowPreferencesDto(followingId, false, false, false)
                : new FollowPreferencesDto(
                        row.followingId(),
                        true,
                        row.notifyNewContent(),
                        row.muted()
                );
    }

    @Transactional
    public FollowPreferencesDto updatePreferences(
            String followerId,
            String followingId,
            FollowPreferencesRequest request
    ) {
        if (request.notifyNewContent() == null && request.muted() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one follow preference is required.");
        }
        lockUser(followerId);
        FollowPreferencesDto current = preferences(followerId, followingId);
        if (!current.following()) {
            throw new ResponseStatusException(NOT_FOUND, "Follow relationship not found.");
        }
        boolean notifyNewContent = request.notifyNewContent() == null
                ? current.notifyNewContent()
                : request.notifyNewContent();
        boolean muted = request.muted() == null ? current.muted() : request.muted();
        socialMapper.updatePreferences(
                followerId,
                followingId,
                notifyNewContent,
                muted
        );

        if (muted) {
            socialMapper.deleteFeedEntries(followerId, followingId);
        } else if (current.muted()) {
            backfillFeed(followerId, followingId);
        }
        return new FollowPreferencesDto(followingId, true, notifyNewContent, muted);
    }

    public PageResponse<UserListItemDto> followers(
            String userId,
            String viewerUserId,
            String keyword,
            int page,
            int pageSize
    ) {
        return relationList(userId, viewerUserId, keyword, page, pageSize, true);
    }

    public PageResponse<UserListItemDto> following(
            String userId,
            String viewerUserId,
            String keyword,
            int page,
            int pageSize
    ) {
        return relationList(userId, viewerUserId, keyword, page, pageSize, false);
    }

    private PageResponse<UserListItemDto> relationList(
            String userId,
            String viewerUserId,
            String keyword,
            int page,
            int pageSize,
            boolean followerList
    ) {
        assertUserExists(userId);
        String viewer = viewerUserId == null || viewerUserId.isBlank() ? "" : viewerUserId.trim();
        if (!viewer.isEmpty()) {
            assertUserExists(viewer);
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        long offsetValue = (long) (safePage - 1) * safePageSize;
        int offset = (int) Math.min(Integer.MAX_VALUE, offsetValue);
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String keywordPattern = normalizedKeyword.isEmpty()
                ? null
                : "%" + normalizedKeyword + "%";
        List<UserListItemDto> items = socialMapper.findRelations(
                        userId,
                        viewer,
                        keywordPattern,
                        followerList,
                        safePageSize,
                        offset
                ).stream()
                .map(SocialService::toUserListItem)
                .toList();
        long total = socialMapper.countRelations(userId, keywordPattern, followerList);
        return PageResponse.of(items, safePage, safePageSize, total);
    }

    private FollowActionResultDto actionResult(String followerId, String followingId, boolean changed) {
        boolean following = isFollowing(followerId, followingId);
        boolean followedBy = isFollowing(followingId, followerId);
        return new FollowActionResultDto(
                changed,
                followingId,
                following,
                followedBy,
                following && followedBy,
                followerCount(followingId),
                followingCount(followerId)
        );
    }

    private void incrementSocialCounts(String followerId, String followingId) {
        ensureSocialStats(followerId);
        ensureSocialStats(followingId);
        socialMapper.incrementFollowingCount(followerId);
        socialMapper.incrementFollowerCount(followingId);
    }

    private void decrementSocialCounts(String followerId, String followingId) {
        ensureSocialStats(followerId);
        ensureSocialStats(followingId);
        socialMapper.decrementFollowingCount(followerId);
        socialMapper.decrementFollowerCount(followingId);
    }

    private void ensureSocialStats(String userId) {
        socialMapper.insertSocialStatsIfAbsent(userId);
    }

    private void backfillFeed(String followerId, String followingId) {
        if (followBackfillSize == 0) {
            return;
        }
        socialMapper.backfillFeed(followerId, followingId, followBackfillSize);
    }

    private long followerCount(String userId) {
        Long value = socialMapper.findFollowerCount(userId);
        return value == null ? 0 : value;
    }

    private long followingCount(String userId) {
        Long value = socialMapper.findFollowingCount(userId);
        return value == null ? 0 : value;
    }

    private boolean isFollowing(String followerId, String followingId) {
        return socialMapper.countFollow(followerId, followingId) > 0;
    }

    private void assertUserExists(String userId) {
        if (socialMapper.countUserById(userId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
    }

    private void lockUser(String userId) {
        socialMapper.lockUser(userId);
    }

    private static UserListItemDto toUserListItem(RelationRow row) {
        boolean following = row.viewerFollowing();
        boolean followedBy = row.viewerFollowedBy();
        return new UserListItemDto(
                row.id(),
                row.handle(),
                row.nickname(),
                row.avatarUrl(),
                row.bio(),
                row.location(),
                row.followerCount(),
                row.followingCount(),
                following,
                followedBy,
                following && followedBy
        );
    }
}
