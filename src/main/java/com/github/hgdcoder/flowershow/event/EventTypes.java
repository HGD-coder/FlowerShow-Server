package com.github.hgdcoder.flowershow.event;

public final class EventTypes {

    public static final String COMMENT_CREATED = "COMMENT_CREATED";
    public static final String COMMENT_LIKED = "COMMENT_LIKED";
    public static final String CONTENT_PUBLISHED = "CONTENT_PUBLISHED";
    public static final String CONTENT_LIKED = "CONTENT_LIKED";
    public static final String CONTENT_FAVORITED = "CONTENT_FAVORITED";
    public static final String USER_FOLLOWED = "USER_FOLLOWED";
    public static final String RECOMMENDATION_BEHAVIOR = "RECOMMENDATION_BEHAVIOR";
    public static final String CHAT_MESSAGE_CREATED = "CHAT_MESSAGE_CREATED";
    public static final String CHAT_GROUP_OWNER_TRANSFERRED = "CHAT_GROUP_OWNER_TRANSFERRED";
    public static final String CHAT_GROUP_DISSOLVED = "CHAT_GROUP_DISSOLVED";

    private EventTypes() {
    }
}
