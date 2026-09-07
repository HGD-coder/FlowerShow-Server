package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.repository.ContentRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    /**
     * Upper bound on results per search request. Keyword matching itself is
     * scored in SQL and ordered by relevance, so only the top matches are
     * materialized instead of the whole content table.
     */
    static final int MAX_SEARCH_RESULTS = 200;

    private final ContentRepository repository;

    public SearchService(ContentRepository repository) {
        this.repository = repository;
    }

    public List<CardItemDto> search(String keyword) {
        return search(keyword, null);
    }

    public List<CardItemDto> search(String keyword, String viewerUserId) {
        String query = normalize(keyword);
        if (query.isBlank()) {
            return List.of();
        }
        return repository.search(query, viewerUserId, MAX_SEARCH_RESULTS);
    }

    private static String normalize(String keyword) {
        return keyword == null
                ? ""
                : keyword.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
