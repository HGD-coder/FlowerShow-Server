package com.github.hgdcoder.flowershow.model;

import java.util.List;

public record MediaCrawlerImportResult(
        int read,
        int inserted,
        int updated,
        int skipped,
        List<String> errors
) {
    public int imported() {
        return inserted + updated;
    }
}
