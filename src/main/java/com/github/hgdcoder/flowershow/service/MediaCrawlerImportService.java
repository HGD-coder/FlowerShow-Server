package com.github.hgdcoder.flowershow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.model.MediaCrawlerImportRequest;
import com.github.hgdcoder.flowershow.model.MediaCrawlerImportResult;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ImportedContentCommand;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ImportedContentStatsCommand;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ImportedMediaAssetCommand;
import com.github.hgdcoder.flowershow.persistence.mapper.content.ImportedUserCommand;
import com.github.hgdcoder.flowershow.persistence.mapper.content.MediaCrawlerImportMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class MediaCrawlerImportService {

    private final MediaCrawlerImportMapper importMapper;
    private final ObjectMapper objectMapper;

    public MediaCrawlerImportService(MediaCrawlerImportMapper importMapper, ObjectMapper objectMapper) {
        this.importMapper = importMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MediaCrawlerImportResult importJsonl(MediaCrawlerImportRequest request) {
        Path path = Paths.get(request.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(BAD_REQUEST, "JSONL file not found: " + path);
        }

        ImportCounter counter = new ImportCounter();
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                counter.read++;
                try {
                    JsonNode raw = objectMapper.readTree(trimmed);
                    ImportAction action = importOne(raw, trimmed, request);
                    if (action == ImportAction.INSERTED) {
                        counter.inserted++;
                    } else if (action == ImportAction.UPDATED) {
                        counter.updated++;
                    } else {
                        counter.skipped++;
                    }
                } catch (Exception e) {
                    counter.skipped++;
                    errors.add("line " + lineNumber + ": " + e.getMessage());
                    if (errors.size() >= 20) {
                        errors.add("More errors omitted.");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Cannot read JSONL file: " + path, e);
        }

        return new MediaCrawlerImportResult(counter.read, counter.inserted, counter.updated, counter.skipped, errors);
    }

    private ImportAction importOne(JsonNode raw, String rawJson, MediaCrawlerImportRequest request) {
        if (isImageRecord(raw)) {
            return importImageOne(raw, rawJson, request);
        }

        String awemeId = text(raw, "aweme_id");
        if (isBlank(awemeId)) {
            return ImportAction.SKIPPED;
        }

        String title = firstNonBlank(text(raw, "title"), text(raw, "desc"), "Untitled video");
        String authorId = authorId(raw);
        upsertUser(authorId, raw);

        boolean exists = importMapper.countContentById(awemeId) > 0;
        upsertContent(awemeId, authorId, raw, title, rawJson);
        upsertStats(awemeId, raw);
        replaceTags(awemeId, raw);
        replaceRecommendWords(awemeId, title);
        if (request.shouldReplaceMediaAssets()) {
            replaceVideoMediaAssets(awemeId, raw, request.effectiveAssetBaseUrl());
        }

        return exists ? ImportAction.UPDATED : ImportAction.INSERTED;
    }

    private ImportAction importImageOne(JsonNode raw, String rawJson, MediaCrawlerImportRequest request) {
        String contentId = firstNonBlank(text(raw, "id"), text(raw, "aweme_id"));
        if (isBlank(contentId)) {
            return ImportAction.SKIPPED;
        }

        List<MediaReference> imageMedia = imageMedia(raw, request.effectiveAssetBaseUrl());
        if (imageMedia.isEmpty()) {
            return ImportAction.SKIPPED;
        }

        String title = firstNonBlank(text(raw, "title"), text(raw, "desc"), "Untitled album");
        String authorId = authorId(raw);
        upsertUser(authorId, raw);

        boolean exists = importMapper.countContentById(contentId) > 0;
        String type = imageMedia.size() == 1 ? "image" : "album";
        upsertContent(
                contentId,
                authorId,
                raw,
                title,
                rawJson,
                type,
                imageMedia.get(0).url(),
                firstNonBlank(text(raw, "aweme_url"), text(raw, "source_url")),
                longValue(raw, "create_time")
        );
        upsertStats(contentId, raw);
        replaceTags(contentId, raw);
        replaceRecommendWords(contentId, title);
        if (request.shouldReplaceMediaAssets()) {
            replaceImageMediaAssets(contentId, imageMedia, raw, request.effectiveAssetBaseUrl());
        }

        return exists ? ImportAction.UPDATED : ImportAction.INSERTED;
    }

    private void upsertUser(String authorId, JsonNode raw) {
        String nickname = firstNonBlank(text(raw, "nickname"), text(raw, "author"), "Unknown creator");
        String avatar = firstNonBlank(text(raw, "avatar"), text(raw, "avatar_url"), text(raw, "avatarUrl"));
        String bio = text(raw, "user_signature");
        String location = text(raw, "ip_location");
        String secUid = text(raw, "sec_uid");

        ImportedUserCommand user = new ImportedUserCommand(
                authorId,
                limit(nickname, 80),
                blankToNull(avatar),
                limit(blankToNull(bio), 255),
                limit(blankToNull(location), 80),
                limit(blankToNull(secUid), 128)
        );
        if (importMapper.countUserById(authorId) > 0) {
            importMapper.updateUser(user);
        } else {
            importMapper.insertUser(user);
            importMapper.insertUserSocialStats(authorId);
            importMapper.insertNotificationUnreadStats(authorId);
        }
    }

    private void upsertContent(String contentId, String authorId, JsonNode raw, String title, String rawJson) {
        upsertContent(
                contentId,
                authorId,
                raw,
                title,
                rawJson,
                "video",
                text(raw, "cover_url"),
                text(raw, "aweme_url"),
                longValue(raw, "create_time")
        );
    }

    private void upsertContent(
            String contentId,
            String authorId,
            JsonNode raw,
            String title,
            String rawJson,
            String type,
            String coverUrl,
            String sourceUrl,
            long publishTime
    ) {
        String description = firstNonBlank(text(raw, "desc"), title);
        ImportedContentCommand content = new ImportedContentCommand(
                contentId,
                type,
                limit(title, 255),
                blankToNull(description),
                authorId,
                blankToNull(coverUrl),
                blankToNull(sourceUrl),
                publishTime,
                rawJson
        );
        if (importMapper.countContentById(contentId) > 0) {
            importMapper.updateContent(content);
        } else {
            importMapper.insertContent(content);
        }
    }

    private void upsertStats(String contentId, JsonNode raw) {
        int likes = intValue(raw, "liked_count");
        int comments = intValue(raw, "comment_count");
        int favorites = intValue(raw, "collected_count");
        int shares = intValue(raw, "share_count");
        ImportedContentStatsCommand stats = new ImportedContentStatsCommand(
                contentId,
                likes,
                comments,
                favorites,
                shares
        );
        if (importMapper.countContentStatsById(contentId) > 0) {
            importMapper.updateContentStats(stats);
        } else {
            importMapper.insertContentStats(stats);
        }
    }

    private void replaceTags(String contentId, JsonNode raw) {
        importMapper.deleteTags(contentId);
        String keyword = text(raw, "source_keyword");
        if (!isBlank(keyword)) {
            importMapper.insertTag(contentId, limit(keyword, 80), 0);
        }
    }

    private void replaceRecommendWords(String contentId, String title) {
        importMapper.deleteRecommendWords(contentId);
        if (!isBlank(title)) {
            importMapper.insertRecommendWord(contentId, limit(title, 80), 0);
        }
    }

    private void replaceVideoMediaAssets(String contentId, JsonNode raw, String assetBaseUrl) {
        importMapper.deleteNonHlsMediaAssets(contentId);

        int order = 0;
        String coverUrl = text(raw, "cover_url");
        if (!isBlank(coverUrl)) {
            insertMedia(contentId, "cover", new MediaReference(coverUrl, null), null, order++);
        }

        JsonNode qualityUrls = raw.get("quality_urls");
        if (qualityUrls != null && qualityUrls.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = qualityUrls.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String quality = field.getKey();
                MediaReference media = mediaReference(field.getValue().asText(""), assetBaseUrl);
                if (media != null) {
                    insertMedia(contentId, "video", media, limit(quality, 20), order++);
                }
            }
        }

        if (order == 0 || !hasVideoAsset(contentId)) {
            MediaReference video = mediaReference(text(raw, "video_download_url"), assetBaseUrl);
            if (video != null) {
                insertMedia(contentId, "video", video, "source", order++);
            }
        }

        MediaReference music = mediaReference(text(raw, "music_download_url"), assetBaseUrl);
        if (music != null) {
            insertMedia(contentId, "music", music, null, order);
        }
    }

    private void replaceImageMediaAssets(String contentId, List<MediaReference> imageMedia, JsonNode raw, String assetBaseUrl) {
        importMapper.deleteMediaAssets(contentId);

        int order = 0;
        for (MediaReference image : imageMedia) {
            insertMedia(contentId, "image", image, null, order++);
        }

        MediaReference music = mediaReference(text(raw, "music_download_url"), assetBaseUrl);
        if (music != null) {
            insertMedia(contentId, "music", music, null, order);
        }
    }

    private void insertMedia(String contentId, String kind, MediaReference media, String quality, int sortOrder) {
        importMapper.insertMediaAsset(new ImportedMediaAssetCommand(
                contentId,
                kind,
                media.url(),
                media.storageKey(),
                quality,
                sortOrder,
                deliveryType(media.url()),
                containerFormat(media.url())
        ));
    }

    private boolean hasVideoAsset(String contentId) {
        return importMapper.countVideoAssets(contentId) > 0;
    }

    private String authorId(JsonNode raw) {
        String userId = text(raw, "user_id");
        if (!isBlank(userId)) {
            return limit("dy_" + cleanId(userId), 64);
        }

        String fallback = firstNonBlank(
                text(raw, "sec_uid"),
                text(raw, "nickname"),
                text(raw, "author"),
                text(raw, "id"),
                text(raw, "aweme_id"),
                "unknown"
        );
        return "dy_" + UUID.nameUUIDFromBytes(fallback.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 24);
    }

    private MediaReference mediaReference(String sourceUrl, String assetBaseUrl) {
        if (isBlank(sourceUrl)) {
            return null;
        }
        String trimmed = sourceUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return new MediaReference(trimmed, null);
        }

        String storageKey = trimmed.replaceAll("^/+", "");
        if (isBlank(storageKey)) {
            return null;
        }

        if (isBlank(assetBaseUrl)) {
            return new MediaReference(trimmed, storageKey);
        }
        String url = assetBaseUrl.replaceAll("/+$", "") + "/" + storageKey;
        return new MediaReference(url, storageKey);
    }

    private boolean isImageRecord(JsonNode raw) {
        return raw.has("image_urls")
                || raw.has("imageUrls")
                || (!isBlank(text(raw, "note_download_url"))
                    && isBlank(text(raw, "video_download_url"))
                    && raw.get("quality_urls") == null
                    && raw.get("qualityUrls") == null);
    }

    private List<MediaReference> imageMedia(JsonNode raw, String assetBaseUrl) {
        JsonNode source = firstExisting(raw, "image_urls", "imageUrls", "note_download_url");
        List<MediaReference> media = new ArrayList<>();
        if (source == null || source.isNull()) {
            return media;
        }
        if (source.isArray()) {
            for (JsonNode item : source) {
                MediaReference reference = mediaReference(item.asText(""), assetBaseUrl);
                if (reference != null && !media.contains(reference)) {
                    media.add(reference);
                }
            }
            return media;
        }
        String rawValue = source.asText("");
        for (String value : rawValue.split(",")) {
            MediaReference reference = mediaReference(value, assetBaseUrl);
            if (reference != null && !media.contains(reference)) {
                media.add(reference);
            }
        }
        return media;
    }

    private JsonNode firstExisting(JsonNode raw, String... fields) {
        for (String field : fields) {
            JsonNode node = raw.get(field);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private String deliveryType(String url) {
        String format = containerFormat(url);
        return "hls".equals(format) ? "hls" : "progressive";
    }

    private String containerFormat(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase();
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) {
            lower = lower.substring(0, queryIndex);
        }
        if (lower.endsWith(".m3u8")) {
            return "hls";
        }
        if (lower.endsWith(".mp4")) {
            return "mp4";
        }
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) {
            return "jpeg";
        }
        if (lower.endsWith(".png")) {
            return "png";
        }
        if (lower.endsWith(".mp3")) {
            return "mp3";
        }
        return null;
    }

    private static String text(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return isBlank(value) ? null : value;
    }

    private static int intValue(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText("0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longValue(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        if (node == null || node.isNull()) {
            return Instant.now().getEpochSecond();
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText("0"));
        } catch (NumberFormatException e) {
            return Instant.now().getEpochSecond();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String cleanId(String value) {
        return value.replaceAll("[^A-Za-z0-9_:-]", "_");
    }

    private enum ImportAction {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    private static final class ImportCounter {
        private int read;
        private int inserted;
        private int updated;
        private int skipped;
    }

    private record MediaReference(String url, String storageKey) {
    }
}
