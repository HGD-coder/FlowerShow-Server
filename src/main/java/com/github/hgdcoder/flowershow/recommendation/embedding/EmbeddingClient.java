package com.github.hgdcoder.flowershow.recommendation.embedding;

import java.util.List;

public interface EmbeddingClient {

    List<float[]> embed(List<String> inputs);
}
