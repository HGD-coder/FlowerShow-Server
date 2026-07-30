package com.github.hgdcoder.flowershow.model;

public enum ProfileContentTab {
    POSTS("posts"),
    LIKED("liked"),
    FAVORITES("favorites");

    private final String apiValue;

    ProfileContentTab(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
