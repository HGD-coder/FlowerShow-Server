package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.GenerateAuthorAccountsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flower_show_accounts_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "flower-show.events.outbox.fixed-delay-ms=600000"
})
class AuthorAccountServiceTest {

    @Autowired
    AuthorAccountService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void generatesAccountsForAllContentAuthorsIdempotentlyAndCanOverwriteExistingPasswords() {
        jdbcTemplate.update("""
                insert into users (id, nickname)
                values ('image_only_author', 'Image Only Author'), ('album_only_author', 'Album Only Author')
                """);
        jdbcTemplate.update("""
                insert into content_items (id, type, title, author_user_id)
                values
                    ('image_only_content', 'image', 'Image only content', 'image_only_author'),
                    ('album_only_content', 'album', 'Album only content', 'album_only_author')
                """);
        jdbcTemplate.update("""
                insert into accounts (id, user_id, username, password_hash)
                values ('existing_album_account', 'album_only_author', 'existing_album_author', 'existing-password-hash')
                """);

        var first = service.generateForVideoAuthors(new GenerateAuthorAccountsRequest("Demo@123456", false));

        assertTrue(first.totalAuthors() > 0);
        assertEquals(first.totalAuthors() - 1, first.created());
        assertEquals(1, first.existing());
        assertEquals(first.totalAuthors(), first.accounts().size());
        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from accounts where user_id = 'image_only_author'",
                Integer.class
        ));
        assertEquals("existing-password-hash", jdbcTemplate.queryForObject(
                "select password_hash from accounts where user_id = 'album_only_author'",
                String.class
        ));

        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from accounts where user_id = 'image_only_author'",
                String.class
        );
        assertNotNull(passwordHash);
        assertTrue(passwordHash.startsWith("$2"));

        var second = service.generateForVideoAuthors(new GenerateAuthorAccountsRequest("Demo@123456", false));

        assertEquals(first.totalAuthors(), second.totalAuthors());
        assertEquals(0, second.created());
        assertEquals(first.totalAuthors(), second.existing());

        var overwrite = service.generateForVideoAuthors(new GenerateAuthorAccountsRequest("Changed@123456", true));

        assertEquals(0, overwrite.created());
        assertEquals(0, overwrite.existing());
        assertEquals(first.totalAuthors(), overwrite.passwordUpdated());
        String overwrittenPasswordHash = jdbcTemplate.queryForObject(
                "select password_hash from accounts where user_id = 'album_only_author'",
                String.class
        );
        assertNotNull(overwrittenPasswordHash);
        assertFalse("existing-password-hash".equals(overwrittenPasswordHash));
        assertTrue(overwrittenPasswordHash.startsWith("$2"));
    }
}
