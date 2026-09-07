package com.github.hgdcoder.flowershow.repository;

import com.github.hgdcoder.flowershow.model.AlbumCardDto;
import com.github.hgdcoder.flowershow.model.AlbumSlideDto;
import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CreateContentRequest;
import com.github.hgdcoder.flowershow.model.ImageCardDto;
import com.github.hgdcoder.flowershow.model.ProfileContentTab;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ContentMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ContentMapper.ContentAssetRow;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ContentMapper.ContentTagRow;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ContentRow;
import com.github.hgdcoder.flowershow.persistence.mapper.content.NewContentCommand;
import com.github.hgdcoder.flowershow.persistence.mapper.content.NewContentMediaAsset;
import com.github.hgdcoder.flowershow.persistence.mapper.content.OrderedContentValue;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ViewerStateRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
public class MyBatisContentRepository implements ContentRepository {

    private final ContentMapper contentMapper;
    private final String mediaPublicBaseUrl;

    public MyBatisContentRepository(
            ContentMapper contentMapper,
            @Value("${flower-show.media.public-base-url:}") String mediaPublicBaseUrl
    ) {
        this.contentMapper = contentMapper;
        this.mediaPublicBaseUrl = mediaPublicBaseUrl == null ? "" : mediaPublicBaseUrl.trim();
    }

    @Override
    public List<CardItemDto> findAllFeedItems(String viewerUserId) {
        return toCards(contentMapper.findAllFeedItems(), viewerUserId);
    }

    @Override
    public List<CardItemDto> findFeedPage(String viewerUserId, int offset, int limit) {
        return toCards(contentMapper.findFeedPage(offset, limit), viewerUserId);
    }

    @Override
    public List<VideoCardDto> findAllVideos(String viewerUserId) {
        return findAllFeedItems(viewerUserId).stream()
                .filter(VideoCardDto.class::isInstance)
                .map(VideoCardDto.class::cast)
                .toList();
    }

    @Override
    public List<VideoCardDto> findVideoPage(String viewerUserId, int offset, int limit) {
        return toCards(contentMapper.findVideoPage(offset, limit), viewerUserId).stream()
                .filter(VideoCardDto.class::isInstance)
                .map(VideoCardDto.class::cast)
                .toList();
    }

    @Override
    public long countPublishedPublicContent(String id) {
        return contentMapper.countPublishedPublicContent(id);
    }

    @Override
    public List<CardItemDto> search(String query, String viewerUserId, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        return toCards(contentMapper.search(query, limit), viewerUserId);
    }

    @Override
    public List<CardItemDto> findUserContent(String userId, String viewerUserId) {
        String profileVisibility = contentMapper.findProfileVisibility(userId);
        String viewer = viewerUserId == null ? "" : viewerUserId.trim();
        if (!userId.equals(viewer) && "private".equalsIgnoreCase(profileVisibility)) {
            throw new ResponseStatusException(FORBIDDEN, "This profile is private.");
        }
        return toCards(contentMapper.findUserContent(userId), viewerUserId);
    }

    @Override
    public List<CardItemDto> findFollowingFeed(String userId, String viewerUserId) {
        return toCards(contentMapper.findFollowingFeed(userId), viewerUserId);
    }

    @Override
    public List<CardItemDto> findProfileContent(
            String userId,
            ProfileContentTab tab,
            boolean ownerView,
            String viewerUserId,
            int offset,
            int limit
    ) {
        List<ContentRow> rows = switch (tab) {
            case POSTS -> contentMapper.findProfilePosts(userId, ownerView, offset, limit);
            case LIKED -> contentMapper.findProfileLiked(userId, ownerView, offset, limit);
            case FAVORITES -> contentMapper.findProfileFavorites(userId, ownerView, offset, limit);
        };
        return toCards(rows, viewerUserId);
    }

    @Override
    public long countProfileContent(String userId, ProfileContentTab tab, boolean ownerView) {
        return switch (tab) {
            case POSTS -> contentMapper.countProfilePosts(userId, ownerView);
            case LIKED -> contentMapper.countProfileLiked(userId, ownerView);
            case FAVORITES -> contentMapper.countProfileFavorites(userId, ownerView);
        };
    }

    @Override
    public Optional<CardItemDto> findById(String id, String viewerUserId) {
        ContentRow row = contentMapper.findById(id);
        if (row == null) {
            return Optional.empty();
        }
        return toCards(List.of(row), viewerUserId).stream().findFirst();
    }

    @Override
    @Transactional
    public CardItemDto saveContent(CreateContentRequest request) {
        String type = normalizeType(request.type());
        assertUserExists(request.authorUserId());

        String id = "cnt_" + UUID.randomUUID().toString().replace("-", "");
        String visibility = normalizeVisibility(request.visibility());
        long publishTime = Instant.now().getEpochSecond();

        contentMapper.insertContent(new NewContentCommand(
                id,
                type,
                request.title().trim(),
                request.description(),
                request.authorUserId(),
                request.coverUrl(),
                visibility,
                publishTime
        ));
        contentMapper.insertContentStats(id);

        List<OrderedContentValue> tags = orderedValues(request.tags());
        if (!tags.isEmpty()) {
            contentMapper.insertTags(id, tags);
        }
        List<OrderedContentValue> recommendWords = orderedValues(request.recommendWords());
        if (!recommendWords.isEmpty()) {
            contentMapper.insertRecommendWords(id, recommendWords);
        }

        insertMedia(id, type, request.mediaUrls(), request.coverUrl());
        return findById(id, request.authorUserId()).orElseThrow();
    }

    private List<CardItemDto> toCards(List<ContentRow> rows, String viewerUserId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, ViewerState> viewerStates = viewerStates(rows, viewerUserId);
        List<String> contentIds = rows.stream().map(ContentRow::id).distinct().toList();

        Map<String, List<String>> tagsById = new HashMap<>();
        for (ContentTagRow tag : contentMapper.findTagsByContentIds(contentIds)) {
            tagsById.computeIfAbsent(tag.contentId(), ignored -> new ArrayList<>()).add(tag.tag());
        }
        Map<String, List<String>> wordsById = new HashMap<>();
        for (ContentTagRow word : contentMapper.findRecommendWordsByContentIds(contentIds)) {
            wordsById.computeIfAbsent(word.contentId(), ignored -> new ArrayList<>()).add(word.tag());
        }
        Map<String, List<ContentAssetRow>> assetsById = new HashMap<>();
        for (ContentAssetRow asset : contentMapper.findAssetsByContentIds(contentIds)) {
            assetsById.computeIfAbsent(asset.contentId(), ignored -> new ArrayList<>()).add(asset);
        }

        return rows.stream()
                .map(row -> toCard(
                        row,
                        viewerStates.getOrDefault(row.id(), ViewerState.NONE),
                        tagsById.getOrDefault(row.id(), List.of()),
                        wordsById.getOrDefault(row.id(), List.of()),
                        assetsById.getOrDefault(row.id(), List.of())
                ))
                .toList();
    }

    private CardItemDto toCard(
            ContentRow row,
            ViewerState viewerState,
            List<String> tags,
            List<String> recommendWords,
            List<ContentAssetRow> assets
    ) {
        return switch (row.type()) {
            case "video" -> new VideoCardDto(
                    "video",
                    row.id(),
                    row.title(),
                    row.nickname(),
                    row.avatarUrl(),
                    firstProgressiveUrl(assets, "video"),
                    row.coverUrl(),
                    firstUrl(assets, "music"),
                    row.likeCount(),
                    row.commentCount(),
                    row.favoriteCount(),
                    row.shareCount(),
                    tags,
                    recommendWords,
                    row.authorUserId(),
                    row.sourceUserId(),
                    row.location(),
                    row.sourceUrl(),
                    row.publishTime(),
                    qualityUrls(assets),
                    hlsUrl(assets),
                    row.authorUserId(),
                    viewerState.liked(),
                    viewerState.favorited()
            );
            case "image" -> new ImageCardDto(
                    "image",
                    row.id(),
                    row.title(),
                    row.nickname(),
                    firstUrl(assets, "image"),
                    row.likeCount(),
                    row.commentCount(),
                    row.authorUserId(),
                    viewerState.liked(),
                    viewerState.favorited()
            );
            case "album" -> new AlbumCardDto(
                    "album",
                    row.id(),
                    row.title(),
                    row.nickname(),
                    row.avatarUrl(),
                    albumSlides(assets),
                    firstUrl(assets, "music"),
                    row.likeCount(),
                    row.commentCount(),
                    row.shareCount(),
                    tags,
                    recommendWords,
                    row.authorUserId(),
                    viewerState.liked(),
                    viewerState.favorited()
            );
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unsupported content type: " + row.type());
        };
    }

    private Map<String, ViewerState> viewerStates(List<ContentRow> rows, String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank() || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, ViewerState> states = new HashMap<>();
        for (int from = 0; from < rows.size(); from += 500) {
            int to = Math.min(from + 500, rows.size());
            List<String> contentIds = rows.subList(from, to).stream()
                    .map(ContentRow::id)
                    .toList();
            for (ViewerStateRow state : contentMapper.findViewerStates(viewerUserId.trim(), contentIds)) {
                states.put(
                        state.contentId(),
                        new ViewerState(state.likedByViewer(), state.favoritedByViewer())
                );
            }
        }
        return states;
    }

    private void insertMedia(String contentId, String type, List<String> mediaUrls, String coverUrl) {
        List<NewContentMediaAsset> assets = new ArrayList<>();
        if (coverUrl != null && !coverUrl.isBlank()) {
            assets.add(new NewContentMediaAsset(contentId, "cover", coverUrl.trim(), 0));
        }

        if (mediaUrls != null) {
            String kind = type.equals("video") ? "video" : "image";
            for (int i = 0; i < mediaUrls.size(); i++) {
                String url = mediaUrls.get(i);
                if (url != null && !url.isBlank()) {
                    assets.add(new NewContentMediaAsset(contentId, kind, url.trim(), i));
                }
            }
        }

        if (!assets.isEmpty()) {
            contentMapper.insertMediaAssets(assets);
        }
    }

    private List<OrderedContentValue> orderedValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<OrderedContentValue> ordered = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value != null && !value.isBlank()) {
                ordered.add(new OrderedContentValue(value.trim(), i));
            }
        }
        return List.copyOf(ordered);
    }

    private String firstUrl(List<ContentAssetRow> assets, String kind) {
        return assets.stream()
                .filter(asset -> kind.equals(asset.kind()))
                .map(asset -> resolveMediaUrl(asset.url(), asset.storageKey()))
                .findFirst()
                .orElse("");
    }

    private String firstProgressiveUrl(List<ContentAssetRow> assets, String kind) {
        return assets.stream()
                .filter(asset -> kind.equals(asset.kind()))
                .filter(asset -> asset.deliveryType() == null
                        || "progressive".equalsIgnoreCase(asset.deliveryType()))
                .map(asset -> resolveMediaUrl(asset.url(), asset.storageKey()))
                .findFirst()
                .orElse("");
    }

    private String hlsUrl(List<ContentAssetRow> assets) {
        ContentAssetRow first = null;
        ContentAssetRow auto = null;
        for (ContentAssetRow asset : assets) {
            if (!"video".equals(asset.kind()) || !"hls".equalsIgnoreCase(asset.deliveryType())) {
                continue;
            }
            if (first == null) {
                first = asset;
            }
            if ("auto".equals(asset.quality()) && auto == null) {
                auto = asset;
            }
        }
        ContentAssetRow chosen = auto != null ? auto : first;
        return chosen == null ? null : resolveMediaUrl(chosen.url(), chosen.storageKey());
    }

    private Map<String, String> qualityUrls(List<ContentAssetRow> assets) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ContentAssetRow asset : assets) {
            if (!"video".equals(asset.kind()) || asset.quality() == null) {
                continue;
            }
            if (asset.deliveryType() != null && !"progressive".equalsIgnoreCase(asset.deliveryType())) {
                continue;
            }
            urls.put(asset.quality(), resolveMediaUrl(asset.url(), asset.storageKey()));
        }
        return urls.isEmpty() ? null : urls;
    }

    private List<AlbumSlideDto> albumSlides(List<ContentAssetRow> assets) {
        List<AlbumSlideDto> slides = new ArrayList<>();
        for (ContentAssetRow asset : assets) {
            if (!"image".equals(asset.kind()) && !"video".equals(asset.kind())) {
                continue;
            }
            slides.add(new AlbumSlideDto(
                    "video".equals(asset.kind()) ? AlbumSlideDto.TYPE_VIDEO : AlbumSlideDto.TYPE_IMAGE,
                    resolveMediaUrl(asset.url(), asset.storageKey())
            ));
        }
        return List.copyOf(slides);
    }

    private String resolveMediaUrl(String url, String storageKey) {
        if (storageKey != null && !storageKey.isBlank() && !mediaPublicBaseUrl.isBlank()) {
            return mediaPublicBaseUrl.replaceAll("/+$", "") + "/" + storageKey.replaceAll("^/+", "");
        }
        return url;
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!List.of("video", "image", "album").contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "type must be video, image, or album.");
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "public"
                : visibility.trim().toLowerCase(Locale.ROOT);
        if (!List.of("public", "private").contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "visibility must be public or private.");
        }
        return normalized;
    }

    private void assertUserExists(String userId) {
        if (contentMapper.countUserById(userId) == 0) {
            throw new ResponseStatusException(NOT_FOUND, "User not found: " + userId);
        }
    }

    private record ViewerState(boolean liked, boolean favorited) {

        private static final ViewerState NONE = new ViewerState(false, false);
    }
}
