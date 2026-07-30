package com.github.hgdcoder.flowershow;

import com.github.hgdcoder.flowershow.model.CreateCommentRequest;
import com.github.hgdcoder.flowershow.model.InteractionResultDto;
import com.github.hgdcoder.flowershow.service.CommentService;
import com.github.hgdcoder.flowershow.service.ContentService;
import com.github.hgdcoder.flowershow.service.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000"
})
class DatabaseIntegrationTest {

    @Autowired
    ContentService contentService;

    @Autowired
    CommentService commentService;

    @Autowired
    InteractionService interactionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void migrationsSeedDataAndInteractionsWork() {
        assertFalse(contentService.feed(1, 5).isEmpty());
        assertFalse(commentService.findComments("v001").isEmpty());

        var comment = commentService.createComment(
                "v001",
                new CreateCommentRequest("seed_u001", null, "Test comment creation and outbox events")
        );
        assertNotNull(comment.id());

        InteractionResultDto result = interactionService.likeContent("v001", "seed_u005");
        assertTrue(result.stats().likeCount() > 0);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where event_type in ('COMMENT_CREATED', 'CONTENT_LIKED')",
                Integer.class
        );
        assertNotNull(outboxCount);
        assertTrue(outboxCount >= 2);
    }
}