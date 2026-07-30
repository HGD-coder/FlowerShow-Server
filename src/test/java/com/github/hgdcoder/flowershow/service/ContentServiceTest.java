package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.repository.InMemoryContentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ContentServiceTest {

    private final ContentService service = new ContentService(new InMemoryContentRepository());

    @Test
    void feedPaginatesMixedCards() {
        List<CardItemDto> firstPage = service.feed(1, 3);

        assertEquals(3, firstPage.size());
        assertInstanceOf(VideoCardDto.class, firstPage.get(0));
    }

    @Test
    void recommendWordsReturnsVideoWords() {
        List<String> words = service.recommendWords("v001");

        assertFalse(words.isEmpty());
    }
}