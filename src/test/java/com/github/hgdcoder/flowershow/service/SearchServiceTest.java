package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.repository.InMemoryContentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    private final SearchService service = new SearchService(new InMemoryContentRepository());

    @Test
    void searchFindsItemsByTitleAndTags() {
        List<CardItemDto> results = service.search("garden");

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(item -> item.title().toLowerCase().contains("garden")));
    }

    @Test
    void blankSearchReturnsEmptyList() {
        assertTrue(service.search("   ").isEmpty());
    }
}