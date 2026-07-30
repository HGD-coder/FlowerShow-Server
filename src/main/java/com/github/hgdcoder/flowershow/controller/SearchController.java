package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.SearchService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<CardItemDto> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String keyword
    ) {
        return searchService.search(keyword, AuthenticatedUser.optionalUserId(jwt));
    }
}
