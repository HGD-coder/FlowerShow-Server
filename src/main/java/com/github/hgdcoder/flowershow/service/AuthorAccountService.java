package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.AccountDto;
import com.github.hgdcoder.flowershow.model.GenerateAuthorAccountsRequest;
import com.github.hgdcoder.flowershow.model.GenerateAuthorAccountsResult;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthorAccountMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthorAccountMapper.AccountRow;
import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthorAccountMapper.AuthorRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorAccountService {

    private final AuthorAccountMapper authorAccountMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthorAccountService(AuthorAccountMapper authorAccountMapper, PasswordEncoder passwordEncoder) {
        this.authorAccountMapper = authorAccountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public GenerateAuthorAccountsResult generateForVideoAuthors(GenerateAuthorAccountsRequest request) {
        GenerateAuthorAccountsRequest safeRequest = request == null
                ? new GenerateAuthorAccountsRequest(null, false)
                : request;
        String password = safeRequest.effectivePassword();
        String passwordHash = passwordEncoder.encode(password);
        List<AuthorRow> authors = findContentAuthors();

        int created = 0;
        int existing = 0;
        int passwordUpdated = 0;

        for (AuthorRow author : authors) {
            AccountRow account = findByUserId(author.userId());
            if (account == null) {
                createAccount(author, passwordHash);
                created++;
            } else if (safeRequest.shouldOverwriteExistingPassword()) {
                updatePassword(account.id(), passwordHash);
                passwordUpdated++;
            } else {
                existing++;
            }
        }

        return new GenerateAuthorAccountsResult(
                authors.size(),
                created,
                existing,
                passwordUpdated,
                password,
                findAccountsForVideoAuthors()
        );
    }

    public List<AccountDto> findAccountsForVideoAuthors() {
        return authorAccountMapper.findAccountsForContentAuthors().stream()
                .map(row -> new AccountDto(
                        row.id(),
                        row.userId(),
                        row.nickname(),
                        row.username(),
                        row.role(),
                        row.status(),
                        row.generated(),
                        toIso(row.createdAt())
                ))
                .toList();
    }

    private List<AuthorRow> findContentAuthors() {
        return authorAccountMapper.findContentAuthors();
    }

    private void createAccount(AuthorRow author, String passwordHash) {
        authorAccountMapper.insertAccount(
                "acc_" + UUID.randomUUID().toString().replace("-", ""),
                author.userId(),
                uniqueUsername(author.userId()),
                passwordHash
        );
    }

    private void updatePassword(String accountId, String passwordHash) {
        authorAccountMapper.updatePassword(
                accountId,
                passwordHash,
                Timestamp.from(Instant.now())
        );
    }

    private AccountRow findByUserId(String userId) {
        return authorAccountMapper.findByUserId(userId);
    }

    private String uniqueUsername(String userId) {
        String base = limit("author_" + cleanUsername(userId), 72);
        String candidate = base;
        int suffix = 2;
        while (usernameExists(candidate)) {
            String tail = "_" + suffix++;
            candidate = limit(base, 80 - tail.length()) + tail;
        }
        return candidate;
    }

    private boolean usernameExists(String username) {
        return authorAccountMapper.countByUsername(username) > 0;
    }

    private static String cleanUsername(String value) {
        String cleaned = value == null ? "unknown" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private static String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
