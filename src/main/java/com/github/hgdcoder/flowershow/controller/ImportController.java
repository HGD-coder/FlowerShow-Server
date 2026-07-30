package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.MediaCrawlerImportRequest;
import com.github.hgdcoder.flowershow.model.MediaCrawlerImportResult;
import com.github.hgdcoder.flowershow.service.MediaCrawlerImportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    private final MediaCrawlerImportService importService;

    public ImportController(MediaCrawlerImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/media-crawler-jsonl")
    public MediaCrawlerImportResult importMediaCrawlerJsonl(@Valid @RequestBody MediaCrawlerImportRequest request) {
        return importService.importJsonl(request);
    }
}
