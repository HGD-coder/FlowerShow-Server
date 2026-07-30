package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.AlbumCardDto;
import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.ImageCardDto;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.repository.ContentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final ContentRepository repository;

    public SearchService(ContentRepository repository) {
        this.repository = repository;
    }

    public List<CardItemDto> search(String keyword) {
        return search(keyword, null);
    }

    public List<CardItemDto> search(String keyword, String viewerUserId) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return List.of();
        }

        return repository.findAllFeedItems(viewerUserId).stream()
                .map(item -> new ScoredItem(item, score(item, query)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredItem::score).reversed())
                .map(ScoredItem::item)
                .toList();
    }

    private static int score(CardItemDto item, String query) {
        int score = 0;
        score += contains(item.title(), query) ? 40 : 0;
        score += contains(item.author(), query) ? 20 : 0;

        if (item instanceof VideoCardDto video) {
            score += listContains(video.tags(), query) ? 20 : 0;
            score += listContains(video.recommendWords(), query) ? 10 : 0;
        } else if (item instanceof AlbumCardDto album) {
            score += listContains(album.tags(), query) ? 20 : 0;
            score += listContains(album.recommendWords(), query) ? 10 : 0;
        } else if (item instanceof ImageCardDto image) {
            score += contains(image.imageUrl(), query) ? 5 : 0;
        }

        return score;
    }

    private static boolean listContains(List<String> values, String query) {
        return values != null && values.stream().anyMatch(value -> contains(value, query));
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private record ScoredItem(CardItemDto item, int score) {
    }
}
