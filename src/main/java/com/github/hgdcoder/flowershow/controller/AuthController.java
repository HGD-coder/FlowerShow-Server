package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.AuthTokenResponse;
import com.github.hgdcoder.flowershow.model.AuthUserDto;
import com.github.hgdcoder.flowershow.model.LoginRequest;
import com.github.hgdcoder.flowershow.model.RefreshTokenRequest;
import com.github.hgdcoder.flowershow.model.RegisterRequest;
import com.github.hgdcoder.flowershow.security.AuthenticatedUser;
import com.github.hgdcoder.flowershow.service.AuthService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthTokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return Map.of("revoked", authService.logout(AuthenticatedUser.userId(jwt), request.refreshToken()));
    }

    @PostMapping("/logout-all")
    public Map<String, Object> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("revokedCount", authService.logoutAll(AuthenticatedUser.userId(jwt)));
    }

    @GetMapping("/me")
    public AuthUserDto me(@AuthenticationPrincipal Jwt jwt) {
        return authService.currentUser(AuthenticatedUser.userId(jwt));
    }
}
