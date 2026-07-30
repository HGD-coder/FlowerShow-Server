package com.github.hgdcoder.flowershow.repository;

import com.github.hgdcoder.flowershow.model.AlbumCardDto;
import com.github.hgdcoder.flowershow.model.AlbumSlideDto;
import com.github.hgdcoder.flowershow.model.CardItemDto;
import com.github.hgdcoder.flowershow.model.CreateContentRequest;
import com.github.hgdcoder.flowershow.model.ImageCardDto;
import com.github.hgdcoder.flowershow.model.ProfileContentTab;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryContentRepository implements ContentRepository {

    private final List<VideoCardDto> videos;
    private final List<CardItemDto> feed;

    public InMemoryContentRepository() {
        this.videos = createVideos();
        this.feed = createFeed(videos);
    }

    @Override
    public List<CardItemDto> findAllFeedItems(String viewerUserId) {
        return feed;
    }

    @Override
    public List<VideoCardDto> findAllVideos(String viewerUserId) {
        return videos;
    }

    @Override
    public List<CardItemDto> findUserContent(String userId, String viewerUserId) {
        return feed;
    }

    @Override
    public List<CardItemDto> findFollowingFeed(String userId, String viewerUserId) {
        return feed;
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
        if (tab != ProfileContentTab.POSTS) {
            return List.of();
        }
        List<CardItemDto> items = findUserContent(userId, viewerUserId);
        if (offset >= items.size()) {
            return List.of();
        }
        return items.subList(offset, Math.min(offset + limit, items.size()));
    }

    @Override
    public long countProfileContent(String userId, ProfileContentTab tab, boolean ownerView) {
        return tab == ProfileContentTab.POSTS ? findUserContent(userId).size() : 0;
    }

    @Override
    public Optional<CardItemDto> findById(String id, String viewerUserId) {
        return feed.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    @Override
    public CardItemDto saveContent(CreateContentRequest request) {
        throw new UnsupportedOperationException("In-memory repository is read-only.");
    }

    private static List<CardItemDto> createFeed(List<VideoCardDto> videos) {
        List<CardItemDto> items = new ArrayList<>();
        items.addAll(videos);
        items.add(new ImageCardDto("image", "img001", "Spring garden color study", "Flower Lens", "https://picsum.photos/720/900?random=30", 89000, 12000, "u001", false, false));
        items.add(new ImageCardDto("image", "img002", "City balcony planting ideas", "Urban Blooms", "https://picsum.photos/720/900?random=31", 42000, 2600, "u002", false, false));
        items.add(new AlbumCardDto(
                "album",
                "alb001",
                "Weekend flower market walk",
                "Market Notes",
                "https://picsum.photos/200/200?random=40",
                List.of(
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=41"),
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=42"),
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=43")
                ),
                "",
                128000,
                8600,
                32000,
                List.of("flowers", "market", "travel"),
                List.of("fresh bouquet", "market route", "seasonal flowers"),
                "u004",
                false,
                false
        ));
        items.add(new AlbumCardDto(
                "album",
                "alb002",
                "Tiny greenhouse inspiration",
                "Plant Lab",
                "https://picsum.photos/200/200?random=50",
                List.of(
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=51"),
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=52"),
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=53"),
                        new AlbumSlideDto(AlbumSlideDto.TYPE_IMAGE, "https://picsum.photos/720/1280?random=54")
                ),
                "",
                76000,
                3900,
                17000,
                List.of("greenhouse", "design", "plants"),
                List.of("small garden", "greenhouse layout", "plant care"),
                "u003",
                false,
                false
        ));
        return List.copyOf(items);
    }

    private static List<VideoCardDto> createVideos() {
        String sintel = "https://media.w3.org/2010/05/sintel/trailer.mp4";
        String bunny = "https://media.w3.org/2010/05/bunny/trailer.mp4";
        String movie = "https://media.w3.org/2010/05/video/movie_300.mp4";

        return List.of(
                new VideoCardDto("video", "v001", "Ten quick flower arrangement ideas", "Flower Lab", "https://picsum.photos/200/200?random=1", sintel, "https://picsum.photos/720/1280?random=11", null, 125000, 8900, 3400, 12000, List.of("flowers", "arrangement", "home"), List.of("small vase", "fresh flowers", "home decor"), "u001", "sec-flower-lab", "Shanghai", "https://example.com/videos/v001", 1714550400L, Map.of("360p", sintel, "720p", sintel), null, "u001", false, false),
                new VideoCardDto("video", "v002", "Balcony garden refresh in one afternoon", "Urban Blooms", "https://picsum.photos/200/200?random=2", bunny, "https://picsum.photos/720/1280?random=12", null, 78000, 5400, 1800, 9600, List.of("balcony", "garden", "plants"), List.of("balcony plants", "garden refresh", "plant shelf"), "u002", "sec-urban-blooms", "Hangzhou", "https://example.com/videos/v002", 1714636800L, Map.of("360p", bunny, "720p", bunny), null, "u002", false, false),
                new VideoCardDto("video", "v003", "How to keep roses fresh longer", "Bloom Care", "https://picsum.photos/200/200?random=3", movie, "https://picsum.photos/720/1280?random=13", null, 67000, 4500, 3200, 15000, List.of("rose", "care", "tutorial"), List.of("rose care", "cut flowers", "flower food"), "u003", "sec-bloom-care", "Suzhou", "https://example.com/videos/v003", 1714723200L, Map.of("360p", movie, "720p", movie), null, "u003", false, false),
                new VideoCardDto("video", "v004", "A slow walk through a spring park", "Travel Petals", "https://picsum.photos/200/200?random=4", bunny, "https://picsum.photos/720/1280?random=14", null, 112000, 7800, 6700, 23000, List.of("travel", "park", "spring"), List.of("spring walk", "park route", "weekend travel"), "u004", "sec-travel-petals", "Dali", "https://example.com/videos/v004", 1714809600L, Map.of("360p", bunny, "720p", bunny), null, "u004", false, false),
                new VideoCardDto("video", "v005", "Minimal desk setup with plants", "Calm Desk", "https://picsum.photos/200/200?random=5", sintel, "https://picsum.photos/720/1280?random=15", null, 234000, 18000, 12000, 45000, List.of("desk", "plants", "lifestyle"), List.of("desk plant", "minimal setup", "plant decor"), "u005", "sec-calm-desk", "Chengdu", "https://example.com/videos/v005", 1714896000L, Map.of("360p", sintel, "720p", sintel), null, "u005", false, false)
        );
    }
}
