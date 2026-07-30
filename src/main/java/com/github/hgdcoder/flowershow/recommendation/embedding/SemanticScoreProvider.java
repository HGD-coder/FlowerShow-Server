package com.github.hgdcoder.flowershow.recommendation.embedding;

import java.util.Map;

public interface SemanticScoreProvider {

    Map<String, Double> searchScores(String normalizedQuery);

    Map<String, Double> feedScores(String actorKey);

    Map<String, Double> suggestionScores(String actorKey);

    double minimumSimilarity();
}
