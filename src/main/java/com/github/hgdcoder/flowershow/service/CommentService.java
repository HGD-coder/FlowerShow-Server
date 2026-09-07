package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.model.CommentDto;
import com.github.hgdcoder.flowershow.model.CreateCommentRequest;
import com.github.hgdcoder.flowershow.persistence.mapper.social.CommentMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.CommentMapper.CommentRow;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CommentService {

    private final CommentMapper commentMapper;
    private final EventPublisher eventPublisher;

    public CommentService(CommentMapper commentMapper, EventPublisher eventPublisher) {
        this.commentMapper = commentMapper;
        this.eventPublisher = eventPublisher;
    }

    public List<CommentDto> findComments(String contentId) {
        requirePubliclyReadableContent(contentId);
        return commentMapper.findVisibleByContentId(contentId).stream()
                .map(CommentService::toDto)
                .toList();
    }

    @Transactional
    public CommentDto createComment(String contentId, CreateCommentRequest request) {
        requirePubliclyReadableContent(contentId);
        assertUserExists(request.userId());
        if (request.parentId() != null && !request.parentId().isBlank()) {
            assertParentCommentBelongsToContent(request.parentId(), contentId);
        }

        String id = "cmt_" + UUID.randomUUID().toString().replace("-", "");
        commentMapper.insertComment(
                id,
                contentId,
                request.userId(),
                blankToNull(request.parentId()),
                request.body().trim()
        );
        commentMapper.incrementContentCommentCount(contentId);
        eventPublisher.publish(DomainEvent.of(
                EventTypes.COMMENT_CREATED,
                "comment",
                id,
                Map.of(
                        "commentId", id,
                        "contentId", contentId,
                        "actorUserId", request.userId(),
                        "parentId", request.parentId() == null ? "" : request.parentId()
                )
        ));
        return findComment(id);
    }

    public CommentDto findComment(String id) {
        CommentRow comment = commentMapper.findById(id);
        if (comment == null) {
            throw new ResponseStatusException(NOT_FOUND, "Comment not found: " + id);
        }
        return toDto(comment);
    }

    private void assertParentCommentBelongsToContent(String parentId, String contentId) {
        if (commentMapper.countParentInContent(parentId, contentId) == 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Parent comment must belong to the same content.");
        }
    }

    private void requirePubliclyReadableContent(String contentId) {
        // Comments are only readable and writable for published public content,
        // so the write path enforces the same rule as the read path; 404 (not
        // 403) keeps unpublished/private items unenumerable.
        if (commentMapper.countPublishedPublicContent(contentId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Content not found: " + contentId);
        }
    }

    private void assertUserExists(String userId) {
        if (commentMapper.countUserById(userId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static CommentDto toDto(CommentRow row) {
        return new CommentDto(
                row.id(),
                row.contentId(),
                row.userId(),
                row.nickname(),
                row.avatarUrl(),
                row.parentId(),
                row.body(),
                row.likeCount(),
                toIso(row.createdAt())
        );
    }
}
