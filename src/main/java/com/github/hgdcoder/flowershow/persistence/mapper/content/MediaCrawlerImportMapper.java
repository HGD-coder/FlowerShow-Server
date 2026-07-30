package com.github.hgdcoder.flowershow.persistence.mapper.content;

import org.apache.ibatis.annotations.Param;

public interface MediaCrawlerImportMapper {

    long countUserById(@Param("userId") String userId);

    long countContentById(@Param("contentId") String contentId);

    long countContentStatsById(@Param("contentId") String contentId);

    long countVideoAssets(@Param("contentId") String contentId);

    void updateUser(@Param("user") ImportedUserCommand user);

    void insertUser(@Param("user") ImportedUserCommand user);

    void insertUserSocialStats(@Param("userId") String userId);

    void insertNotificationUnreadStats(@Param("userId") String userId);

    void updateContent(@Param("content") ImportedContentCommand content);

    void insertContent(@Param("content") ImportedContentCommand content);

    void updateContentStats(@Param("stats") ImportedContentStatsCommand stats);

    void insertContentStats(@Param("stats") ImportedContentStatsCommand stats);

    void deleteTags(@Param("contentId") String contentId);

    void insertTag(
            @Param("contentId") String contentId,
            @Param("tag") String tag,
            @Param("sortOrder") int sortOrder
    );

    void deleteRecommendWords(@Param("contentId") String contentId);

    void insertRecommendWord(
            @Param("contentId") String contentId,
            @Param("word") String word,
            @Param("sortOrder") int sortOrder
    );

    void deleteNonHlsMediaAssets(@Param("contentId") String contentId);

    void deleteMediaAssets(@Param("contentId") String contentId);

    void insertMediaAsset(@Param("asset") ImportedMediaAssetCommand asset);
}
