package com.aitp.orenda.auth;

import com.aitp.orenda.auth.dto.AuthResponse;
import com.aitp.orenda.auth.dto.LoginRequest;
import com.aitp.orenda.auth.dto.ResendVerificationRequest;
import com.aitp.orenda.auth.dto.SignupRequest;
import com.aitp.orenda.auth.dto.SignupResponse;
import com.aitp.orenda.auth.dto.UserResponse;
import com.aitp.orenda.auth.dto.VerifyEmailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Map;

/**
 * User management: email+password signup with email verification, login,
 * Google sign-in, and a {@code /me} endpoint for the authenticated user.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final GoogleOAuthService googleOAuthService;

    @Value("${app.oauth.google.frontend-redirect-uri:}")
    private String frontendRedirectUri;

    /**
     * POST /api/auth/signup — create an account and email a verification link.
     */
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        UserResponse user = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SignupResponse.of(user, true));
    }

    /**
     * GET /api/auth/verify-email?token=... — confirm the email address.
     */
    @GetMapping("/verify-email")
    public VerifyEmailResponse verifyEmail(@RequestParam String token) {
        UserResponse user = authService.verifyEmail(token);
        return new VerifyEmailResponse(true, user);
    }

    /**
     * POST /api/auth/login — exchange credentials for a JWT.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * POST /api/auth/resend-verification — re-send the verification email.
     */
    @PostMapping("/resend-verification")
    public Map<String, Object> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return Map.of("message", "Verification email sent if the address has an account.");
    }

    /**
     * GET /api/auth/google — redirect the browser to Google's account page.
     */
    @GetMapping("/google")
    public ResponseEntity<Void> google() {
        String authorizationUrl = googleOAuthService.buildAuthorizationUrl();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .build();
    }

    /**
     * GET /api/auth/google/callback?code=...&state=... — Google redirects here
     * after the user signs in; the browser is then sent to the frontend with a JWT.
     */
    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam String code, @RequestParam String state) {
        try {
            GoogleOAuthService.GoogleUser googleUser = googleOAuthService.exchangeCode(code, state);
            AuthResponse auth = authService.googleLogin(googleUser);
            return redirectToFrontend("token=" + auth.token());
        } catch (Exception e) {
            return redirectToFrontend("error=" + urlEncode(e.getMessage()));
        }
    }

    /**
     * GET /api/auth/me — current authenticated user.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserEntity user) {
        return AuthService.toResponse(user);
    }

    private ResponseEntity<Void> redirectToFrontend(String query) {
        String target = frontendRedirectUri == null || frontendRedirectUri.isBlank()
                ? "/api/auth/me"
                : frontendRedirectUri + (frontendRedirectUri.contains("?") ? "&" : "?") + query;
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "unknown error" : value, StandardCharsets.UTF_8);
    }
}