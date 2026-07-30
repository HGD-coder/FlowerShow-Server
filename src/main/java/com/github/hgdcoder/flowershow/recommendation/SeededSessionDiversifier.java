package com.github.hgdcoder.flowershow.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SeededSessionDiversifier {

    private static final int CANDIDATE_WINDOW_SIZE = 12;
    private static final double MAX_SCORE_GAP = 0.18;
    private static final double TEMPERATURE = 0.08;
    private static final double AUTHOR_REPEAT_PENALTY = 0.14;
    private static final double MIN_FRACTION = 1.0e-12;

    private SeededSessionDiversifier() {
    }

    static List<String> order(List<Candidate> candidates, String seed) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(seed, "seed");

        Map<String, Candidate> distinct = new LinkedHashMap<>();
        candidates.forEach(candidate -> distinct.putIfAbsent(candidate.id(), candidate));
        List<Candidate> remaining = new ArrayList<>(distinct.values());
        List<String> orderedIds = new ArrayList<>(remaining.size());
        Map<String, Integer> authorCounts = new HashMap<>();

        while (!remaining.isEmpty()) {
            List<AdjustedCandidate> ranked = remaining.stream()
                    .map(candidate -> new AdjustedCandidate(
                            candidate,
                            candidate.baseScore()
                                    - AUTHOR_REPEAT_PENALTY
                                    * authorCounts.getOrDefault(candidate.authorId(), 0)
                    ))
                    .sorted(Comparator.comparingDouble(AdjustedCandidate::score)
                            .reversed()
                            .thenComparing(value -> value.candidate().id()))
                    .toList();
            double bestScore = ranked.get(0).score();
            List<AdjustedCandidate> window = ranked.stream()
                    .limit(CANDIDATE_WINDOW_SIZE)
                    .takeWhile(candidate -> candidate.score() >= bestScore - MAX_SCORE_GAP)
                    .toList();
            int position = orderedIds.size();
            AdjustedCandidate selected = window.stream()
                    .max(Comparator.comparingDouble((AdjustedCandidate candidate) ->
                                    weightedGumbelKey(candidate, seed, position))
                            .thenComparing(candidate -> candidate.candidate().id()))
                    .orElseThrow();

            remaining.remove(selected.candidate());
            authorCounts.merge(selected.candidate().authorId(), 1, Integer::sum);
            orderedIds.add(selected.candidate().id());
        }
        return List.copyOf(orderedIds);
    }

    private static double weightedGumbelKey(
            AdjustedCandidate candidate,
            String seed,
            int position
    ) {
        double fraction = deterministicFraction(
                seed + "|" + position + "|" + candidate.candidate().id()
        );
        double clamped = Math.max(MIN_FRACTION, Math.min(1.0 - MIN_FRACTION, fraction));
        double gumbel = -Math.log(-Math.log(clamped));
        return candidate.score() / TEMPERATURE + gumbel;
    }

    private static double deterministicFraction(String value) {
        String hash = RecommendationActorResolver.sha256Hex(value);
        long bits = Long.parseUnsignedLong(hash.substring(0, 13), 16);
        return bits / (double) 0xFFFFFFFFFFFFFL;
    }

    record Candidate(String id, String authorId, double baseScore) {
        Candidate {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("Candidate id must not be blank.");
            }
            if (!Double.isFinite(baseScore)) {
                throw new IllegalArgumentException("Candidate score must be finite.");
            }
        }
    }

    private record AdjustedCandidate(Candidate candidate, double score) {
    }
}
