package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.model.ContentStatsDto;
import com.github.hgdcoder.flowershow.model.InteractionResultDto;
import com.github.hgdcoder.flowershow.persistence.mapper.social.InteractionMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.InteractionMapper.CommentRef;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class InteractionService {

    private final InteractionMapper interactionMapper;
    private final EventPublisher eventPublisher;

    public InteractionService(InteractionMapper interactionMapper, EventPublisher eventPublisher) {
        this.interactionMapper = interactionMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public InteractionResultDto likeContent(String contentId, String userId) {
        assertContentExists(contentId);
        assertUserExists(userId);
        boolean changed = tryInsertContentLike(contentId, userId);
        if (changed) {
            interactionMapper.incrementContentLikeCount(contentId);
            eventPublisher.publish(DomainEvent.of(
                    EventTypes.CONTENT_LIKED,
                    "content",
                    contentId,
                    Map.of("contentId", contentId, "actorUserId", userId)
            ));
        }
        return new InteractionResultDto(changed, stats(contentId));
    }

    @Transactional
    public InteractionResultDto unlikeContent(String contentId, String userId) {
        assertContentExists(contentId);
        assertUserExists(userId);
        boolean changed = interactionMapper.deleteContentLike(contentId, userId) > 0;
        if (changed) {
            interactionMapper.decrementContentLikeCount(contentId);
        }
        return new InteractionResultDto(changed, stats(contentId));
    }

    @Transactional
    public InteractionResultDto favoriteContent(String contentId, String userId) {
        assertContentExists(contentId);
        assertUserExists(userId);
        lockUser(userId);
        if (isFavorited(contentId, userId)) {
            return new InteractionResultDto(false, stats(contentId));
        }
        String collectionId = ensureDefaultCollection(userId);
        boolean changed = tryInsertCollectionItem(collectionId, contentId);
        if (changed) {
            interactionMapper.incrementContentFavoriteCount(contentId);
            eventPublisher.publish(DomainEvent.of(
                    EventTypes.CONTENT_FAVORITED,
                    "content",
                    contentId,
                    Map.of("contentId", contentId, "actorUserId", userId)
            ));
        }
        return new InteractionResultDto(changed, stats(contentId));
    }

    @Transactional
    public InteractionResultDto unfavoriteContent(String contentId, String userId) {
        assertContentExists(contentId);
        assertUserExists(userId);
        lockUser(userId);
        boolean changed = interactionMapper.deleteDefaultCollectionItem(contentId, userId) > 0;
        if (changed) {
            interactionMapper.decrementContentFavoriteCount(contentId);
        }
        return new InteractionResultDto(changed, stats(contentId));
    }

    @Transactional
    public boolean likeComment(String commentId, String userId) {
        assertUserExists(userId);
        CommentRef comment = findCommentRef(commentId);
        boolean changed = tryInsertCommentLike(commentId, userId);
        if (changed) {
            interactionMapper.incrementCommentLikeCount(commentId);
            eventPublisher.publish(DomainEvent.of(
                    EventTypes.COMMENT_LIKED,
                    "comment",
                    commentId,
                    Map.of(
                            "commentId", commentId,
                            "contentId", comment.contentId(),
                            "actorUserId", userId
                    )
            ));
        }
        return changed;
    }

    public ContentStatsDto stats(String contentId) {
        ContentStatsDto result = interactionMapper.findContentStats(contentId);
        if (result == null) {
            throw new EmptyResultDataAccessException(1);
        }
        return result;
    }

    private String ensureDefaultCollection(String userId) {
        String existingCollectionId = interactionMapper.findDefaultCollectionId(userId);
        if (existingCollectionId != null) {
            return existingCollectionId;
        }
        String collectionId = "col_" + UUID.randomUUID().toString().replace("-", "");
        interactionMapper.insertDefaultCollection(collectionId, userId);
        return collectionId;
    }

    private boolean isFavorited(String contentId, String userId) {
        return interactionMapper.countDefaultFavorite(contentId, userId) > 0;
    }

    private void lockUser(String userId) {
        interactionMapper.lockUser(userId);
    }

    private CommentRef findCommentRef(String commentId) {
        CommentRef comment = interactionMapper.findCommentRef(commentId);
        if (comment == null) {
            throw new ResponseStatusException(NOT_FOUND, "Comment not found: " + commentId);
        }
        return comment;
    }

    private boolean tryInsertContentLike(String contentId, String userId) {
        try {
            interactionMapper.insertContentLike(contentId, userId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private boolean tryInsertCollectionItem(String collectionId, String contentId) {
        try {
            interactionMapper.insertCollectionItem(collectionId, contentId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private boolean tryInsertCommentLike(String commentId, String userId) {
        try {
            interactionMapper.insertCommentLike(commentId, userId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void assertContentExists(String contentId) {
        if (interactionMapper.countContentById(contentId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Content not found: " + contentId);
        }
    }

    private void assertUserExists(String userId) {
        if (interactionMapper.countUserById(userId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
    }
}
