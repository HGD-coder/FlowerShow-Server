package com.github.hgdcoder.flowershow.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private static final Set<String> RECOMMENDATION_PATHS = Set.of(
            "/api/v1/feed/pages",
            "/api/v1/search/guesses",
            "/api/v1/search/videos",
            "/api/v1/events:batch"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/feed/pages",
                                "/api/v1/search/guesses",
                                "/api/v1/search/videos",
                                "/api/v1/events:batch"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/health",
                                "/api/v1/feed",
                                "/api/v1/videos",
                                "/api/v1/videos/**",
                                "/api/v1/search",
                                "/api/v1/users",
                                "/api/v1/users/*",
                                "/api/v1/users/*/profile",
                                "/api/v1/users/*/profile/contents",
                                "/api/v1/users/*/contents",
                                "/api/v1/users/*/followers",
                                "/api/v1/users/*/following",
                                "/api/v1/contents/*/comments",
                                "/uploads/**",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/v1/accounts/**", "/api/v1/import/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeAuthenticationError(request, response))
                )
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeAuthenticationError(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Access is denied."))
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        String configuredSecret = properties.secret();
        String secretValue = configuredSecret == null || configuredSecret.isBlank()
                ? loadOrCreateLocalSecret(properties.secretFile())
                : configuredSecret;
        byte[] secret = secretValue.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey) {
        JWKSource<SecurityContext> keySource = new ImmutableSecret<>(secretKey);
        return new NimbusJwtEncoder(keySource);
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> audience = token -> {
            if (token.getAudience().contains(properties.audience())) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The required audience is missing.",
                    null
            ));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"data\":null}");
    }

    private static void writeAuthenticationError(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (!RECOMMENDATION_PATHS.contains(request.getRequestURI())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication is required.");
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"requestId":"%s","traceId":"%s","serverTimeMs":%d,"data":null,\
                "error":{"code":"UNAUTHORIZED","message":"Authentication is required.","details":null}}
                """.formatted(
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis()
        ));
    }

    private static String loadOrCreateLocalSecret(String secretFile) {
        Path path = Path.of(secretFile == null || secretFile.isBlank()
                        ? "data/jwt-secret.local"
                        : secretFile)
                .toAbsolutePath()
                .normalize();
        try {
            if (Files.exists(path)) {
                lockDownSecretFile(path);
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Files.writeString(
                    path,
                    generated,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            lockDownSecretFile(path);
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load or create the local JWT secret file: " + path, e);
        }
    }

    private static void lockDownSecretFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return;
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows): best-effort ACL tightening below.
        } catch (IOException ignored) {
            return;
        }
        java.io.File file = path.toFile();
        file.setReadable(true, true);
        file.setWritable(true, true);
        file.setReadable(false, false);
        file.setWritable(false, false);
    }
}
