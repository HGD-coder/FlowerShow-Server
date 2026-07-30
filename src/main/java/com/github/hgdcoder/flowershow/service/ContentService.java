package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.event.DomainEvent;
import com.github.hgdcoder.flowershow.event.EventPublisher;
import com.github.hgdcoder.flowershow.event.EventTypes;
import com.github.hgdcoder.flowershow.model.AlbumCardDto;
import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CreateContentRequest;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.repository.ContentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentService {

    private final ContentRepository repository;
    private final EventPublisher eventPublisher;

    @Autowired
    public ContentService(ContentRepository repository, EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public ContentService(ContentRepository repository) {
        this(repository, event -> {
        });
    }

    public List<CardItemDto> feed(int page, int pageSize) {
        return feed(page, pageSize, null);
    }

    public List<CardItemDto> feed(int page, int pageSize, String viewerUserId) {
        return paginate(repository.findAllFeedItems(viewerUserId), page, pageSize);
    }

    public List<VideoCardDto> videos(int page, int pageSize) {
        return videos(page, pageSize, null);
    }

    public List<VideoCardDto> videos(int page, int pageSize, String viewerUserId) {
        return paginate(repository.findAllVideos(viewerUserId), page, pageSize);
    }

    public List<CardItemDto> userContent(String userId) {
        return userContent(userId, null);
    }

    public List<CardItemDto> userContent(String userId, String viewerUserId) {
        return repository.findUserContent(userId, viewerUserId);
    }

    public List<CardItemDto> followingFeed(String userId) {
        return repository.findFollowingFeed(userId, userId);
    }

    @Transactional
    public CardItemDto createContent(CreateContentRequest request) {
        CardItemDto created = repository.saveContent(request);
        eventPublisher.publish(DomainEvent.of(
                EventTypes.CONTENT_PUBLISHED,
                "content",
                created.id(),
                Map.of(
                        "contentId", created.id(),
                        "authorUserId", request.authorUserId(),
                        "title", created.title()
                )
        ));
        return created;
    }

    public List<String> recommendWords(String id) {
        return repository.findById(id)
                .map(this::recommendWordsFor)
                .orElse(List.of());
    }

    private List<String> recommendWordsFor(CardItemDto item) {
        if (item instanceof VideoCardDto video) {
            return video.recommendWords();
        }
        if (item instanceof AlbumCardDto album) {
            return album.recommendWords();
        }
        return List.of();
    }

    private static <T> List<T> paginate(List<T> items, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        int from = (safePage - 1) * safePageSize;
        if (from >= items.size()) {
            return List.of();
        }
        int to = Math.min(from + safePageSize, items.size());
        return items.subList(from, to);
    }
}
