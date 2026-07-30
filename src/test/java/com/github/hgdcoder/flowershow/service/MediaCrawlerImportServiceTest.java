package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.MediaCrawlerImportRequest;
import com.github.hgdcoder.flowershow.model.VideoCardDto;
import com.github.hgdcoder.flowershow.service.ContentService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_import_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.media.public-base-url=https://cdn.public.example/media",
        "flower-show.events.outbox.fixed-delay-ms=600000"
})
class MediaCrawlerImportServiceTest {

    @Autowired
    MediaCrawlerImportService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ContentService contentService;

    @TempDir
    Path tempDir;

    @Test
    void importsAndUpdatesMediaCrawlerJsonl() throws Exception {
        Path jsonl = tempDir.resolve("video_data.jsonl");
        Files.writeString(jsonl, """
                {"aweme_id":"756","title":"Imported video","desc":"Imported desc","create_time":1761566400,"user_id":"641","sec_uid":"sec-641","nickname":"Imported Creator","avatar":"https://avatar","liked_count":"12","collected_count":"3","comment_count":"4","share_count":"5","ip_location":"Shanghai","aweme_url":"https://www.douyin.com/video/756","cover_url":"https://cover","video_download_url":"https://source-video","music_download_url":"https://music","source_keyword":"food","quality_urls":{"720p":"videos/756/video_720p.mp4","360p":"videos/756/video_360p.mp4"}}
                {"aweme_id":"756","title":"Imported video updated","desc":"Imported desc 2","create_time":1761566500,"user_id":"641","sec_uid":"sec-641","nickname":"Imported Creator 2","liked_count":"20","collected_count":"8","comment_count":"6","share_count":"9","source_keyword":"recipe","quality_urls":{"720p":"videos/756/video_720p_v2.mp4"}}
                """, StandardCharsets.UTF_8);

        var result = service.importJsonl(new MediaCrawlerImportRequest(
                jsonl.toString(),
                "http://10.0.2.2:8080",
                true
        ));

        assertEquals(2, result.read());
        assertEquals(1, result.inserted());
        assertEquals(1, result.updated());
        assertEquals(0, result.skipped());

        String title = jdbcTemplate.queryForObject(
                "select title from content_items where id = '756'",
                String.class
        );
        assertEquals("Imported video updated", title);

        Integer likes = jdbcTemplate.queryForObject(
                "select like_count from content_stats where content_id = '756'",
                Integer.class
        );
        assertEquals(20, likes);

        Integer mediaCount = jdbcTemplate.queryForObject(
                "select count(*) from media_assets where content_id = '756'",
                Integer.class
        );
        assertTrue(mediaCount != null && mediaCount >= 1);

        String mediaUrl = jdbcTemplate.queryForObject(
                "select url from media_assets where content_id = '756' and kind = 'video' and quality = '720p'",
                String.class
        );
        assertEquals("http://10.0.2.2:8080/videos/756/video_720p_v2.mp4", mediaUrl);

        String storageKey = jdbcTemplate.queryForObject(
                "select storage_key from media_assets where content_id = '756' and kind = 'video' and quality = '720p'",
                String.class
        );
        assertEquals("videos/756/video_720p_v2.mp4", storageKey);

        VideoCardDto imported = contentService.feed(1, 5).stream()
                .filter(VideoCardDto.class::isInstance)
                .map(VideoCardDto.class::cast)
                .filter(card -> card.id().equals("756"))
                .findFirst()
                .orElseThrow();
        assertEquals("https://cdn.public.example/media/videos/756/video_720p_v2.mp4", imported.videoUrl());
        assertEquals(
                "https://cdn.public.example/media/videos/756/video_720p_v2.mp4",
                imported.qualityUrls().get("720p")
        );

        jdbcTemplate.update("""
                insert into media_assets
                    (content_id, kind, url, storage_key, quality, sort_order, delivery_type, container_format)
                values
                    ('756', 'video', 'http://localhost/media/videos/756/hls/master.m3u8',
                     'videos/756/hls/master.m3u8', 'auto', 1000, 'hls', 'hls')
                """);
        VideoCardDto withHls = contentService.feed(1, 5).stream()
                .filter(VideoCardDto.class::isInstance)
                .map(VideoCardDto.class::cast)
                .filter(card -> card.id().equals("756"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "https://cdn.public.example/media/videos/756/hls/master.m3u8",
                withHls.hlsUrl()
        );
        assertTrue(withHls.videoUrl().endsWith(".mp4"));

        Files.writeString(jsonl, """
                {"aweme_id":"756","title":"Imported after HLS","create_time":1761566600,"user_id":"641","nickname":"Imported Creator 3","quality_urls":{"480p":"videos/756/video_480p.mp4"}}
                """, StandardCharsets.UTF_8);
        service.importJsonl(new MediaCrawlerImportRequest(
                jsonl.toString(),
                "http://10.0.2.2:8080",
                true
        ));
        Integer hlsCount = jdbcTemplate.queryForObject(
                "select count(*) from media_assets where content_id = '756' and delivery_type = 'hls'",
                Integer.class
        );
        assertEquals(1, hlsCount);
    }

    @Test
    void importsImageJsonlAsAlbumWithCdnUrls() throws Exception {
        Path jsonl = tempDir.resolve("image_data.jsonl");
        Files.writeString(jsonl, """
                {"id":"img756","title":"Imported album","author":"Album Creator","avatar_url":"https://avatar","music_download_url":"music/img756.mp3","image_urls":["images/img756/000.jpeg","images/img756/001.jpeg"],"liked_count":"43","comment_count":"5","share_count":"7","source_keyword":"album-tag"}
                """, StandardCharsets.UTF_8);

        var result = service.importJsonl(new MediaCrawlerImportRequest(
                jsonl.toString(),
                null,
                "https://cdn.example.com/flower-show",
                true
        ));

        assertEquals(1, result.read());
        assertEquals(1, result.inserted());
        assertEquals(0, result.updated());
        assertEquals(0, result.skipped());

        String type = jdbcTemplate.queryForObject(
                "select type from content_items where id = 'img756'",
                String.class
        );
        assertEquals("album", type);

        String coverUrl = jdbcTemplate.queryForObject(
                "select cover_url from content_items where id = 'img756'",
                String.class
        );
        assertEquals("https://cdn.example.com/flower-show/images/img756/000.jpeg", coverUrl);

        Integer imageCount = jdbcTemplate.queryForObject(
                "select count(*) from media_assets where content_id = 'img756' and kind = 'image'",
                Integer.class
        );
        assertEquals(2, imageCount);

        String firstImageUrl = jdbcTemplate.queryForObject(
                "select url from media_assets where content_id = 'img756' and kind = 'image' order by sort_order limit 1",
                String.class
        );
        assertEquals("https://cdn.example.com/flower-show/images/img756/000.jpeg", firstImageUrl);

        String firstStorageKey = jdbcTemplate.queryForObject(
                "select storage_key from media_assets where content_id = 'img756' and kind = 'image' order by sort_order limit 1",
                String.class
        );
        assertEquals("images/img756/000.jpeg", firstStorageKey);
    }
}
