package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CursorPageResponse;
import com.github.hgdcoder.flowershow.persistence.mapper.content.FollowingFeedKey;
import com.github.hgdcoder.flowershow.persistence.mapper.content.FollowingFeedMapper;
import com.github.hgdcoder.flowershow.repository.ContentRepository;
import com.github.hgdcoder.flowershow.util.CursorCodec;
import com.github.hgdcoder.flowershow.util.CursorCodec.Cursor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class FollowingFeedService {

    private final FollowingFeedMapper followingFeedMapper;
    private final ContentRepository contentRepository;
    private final long fanoutOnWriteMaxFollowers;

    public FollowingFeedService(
            FollowingFeedMapper followingFeedMapper,
            ContentRepository contentRepository,
            @Value("${flower-show.feed.fanout-on-write-max-followers:100000}") long fanoutOnWriteMaxFollowers
    ) {
        this.followingFeedMapper = followingFeedMapper;
        this.contentRepository = contentRepository;
        this.fanoutOnWriteMaxFollowers = Math.max(0, fanoutOnWriteMaxFollowers);
    }

    public CursorPageResponse<CardItemDto> page(String userId, String cursorValue, int pageSize) {
        assertUserExists(userId);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        Cursor cursor = CursorCodec.decode(cursorValue);
        List<FollowingFeedKey> keys = queryKeys(userId, cursor, safePageSize + 1);
        boolean hasMore = keys.size() > safePageSize;
        List<FollowingFeedKey> pageKeys = hasMore ? keys.subList(0, safePageSize) : keys;
        List<CardItemDto> items = new ArrayList<>(pageKeys.size());
        for (FollowingFeedKey key : pageKeys) {
            contentRepository.findById(key.contentId(), userId).ifPresent(items::add);
        }
        String nextCursor = hasMore && !pageKeys.isEmpty()
                ? CursorCodec.encode(
                        pageKeys.get(pageKeys.size() - 1).publishTime(),
                        pageKeys.get(pageKeys.size() - 1).contentId()
                )
                : null;
        return new CursorPageResponse<>(List.copyOf(items), nextCursor, hasMore);
    }

    private List<FollowingFeedKey> queryKeys(String userId, Cursor cursor, int limit) {
        return followingFeedMapper.findFeedKeys(
                userId,
                fanoutOnWriteMaxFollowers,
                cursor == null ? null : cursor.sortValue(),
                cursor == null ? null : cursor.id(),
                limit
        );
    }

    private void assertUserExists(String userId) {
        if (followingFeedMapper.countUserById(userId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
    }
}
