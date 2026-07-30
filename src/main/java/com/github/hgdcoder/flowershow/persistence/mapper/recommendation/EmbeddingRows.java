package com.github.hgdcoder.flowershow.persistence.mapper.recommendation;

public final class EmbeddingRows {

    private EmbeddingRows() {
    }

    public record ContentSourceRow(String id, String title, String description) {
    }

    public record WeightedInterestRow(String actorKey, String interestKey, double weight) {
    }

    public record HashRow(String id, String sourceHash) {
    }

    public record ScoreRow(String id, Double similarity) {
    }
}
