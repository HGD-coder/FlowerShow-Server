package com.github.hgdcoder.flowershow.config;

import com.github.hgdcoder.flowershow.persistence.mapper.social.AuthMapper;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the first admin account from ADMIN_BOOTSTRAP_USERNAME /
 * ADMIN_BOOTSTRAP_PASSWORD environment variables. Without this the
 * hasRole("ADMIN") endpoints (/api/v1/accounts, /api/v1/import) are
 * unreachable because no code path ever provisions an admin role.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapUsername;
    private final String bootstrapPassword;

    public AdminBootstrap(
            AuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            @Value("${flower-show.admin.bootstrap-username:}") String bootstrapUsername,
            @Value("${flower-show.admin.bootstrap-password:}") String bootstrapPassword
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapUsername = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapUsername.isBlank() || bootstrapPassword.isBlank()) {
            return;
        }
        if (bootstrapPassword.length() < MIN_PASSWORD_LENGTH) {
            LOGGER.warn(
                    "Admin bootstrap skipped: password must contain at least {} characters.",
                    MIN_PASSWORD_LENGTH
            );
            return;
        }
        String username = bootstrapUsername.toLowerCase(Locale.ROOT);
        if (authMapper.countAccountsByUsername(username) > 0) {
            return;
        }
        if (authMapper.countUsersByNormalizedHandle(username) > 0) {
            LOGGER.warn("Admin bootstrap skipped: handle '{}' is already taken by another user.", username);
            return;
        }

        String userId = "usr_" + compactUuid();
        String accountId = "acc_" + compactUuid();
        authMapper.insertUser(userId, username, username, null, null);
        authMapper.insertAccountWithRole(
                accountId,
                userId,
                username,
                passwordEncoder.encode(bootstrapPassword),
                "admin"
        );
        authMapper.insertUserSocialStats(userId);
        authMapper.insertNotificationUnreadStats(userId);
        LOGGER.info("Bootstrapped admin account '{}' (user id {}).", username, userId);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
