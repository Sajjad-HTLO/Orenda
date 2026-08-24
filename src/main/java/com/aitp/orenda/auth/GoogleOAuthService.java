package com.aitp.orenda.auth;

import com.aitp.orenda.auth.exception.GoogleOAuthException;
import com.aitp.orenda.auth.exception.OAuthNotConfiguredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
public class GoogleOAuthService {

    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final RestClient restClient;
    private final UserRepository userRepository;

    public GoogleOAuthService(
            @Value("${app.oauth.google.client-id:}") String clientId,
            @Value("${app.oauth.google.client-secret:}") String clientSecret,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            UserRepository userRepository) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = baseUrl;
        this.restClient = restClientBuilder.getIfAvailable(RestClient::builder).build();
        this.userRepository = userRepository;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public String redirectUri() {
        return baseUrl + "/api/auth/google/callback";
    }

    /**
     * URL the browser is redirected to, sending the user to their Google account.
     */
    public String buildAuthorizationUrl() {
        if (!isConfigured()) {
            throw new OAuthNotConfiguredException();
        }
        String state = newState();
        userRepository.saveOAuthState(state);
        userRepository.purgeExpiredOAuthStates();

        return AUTHORIZATION_ENDPOINT
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri())
                + "&response_type=code"
                + "&scope=" + urlEncode("openid email profile")
                + "&state=" + state
                + "&access_type=online";
    }

    /**
     * Exchanges the one-time authorization code for a Google user profile.
     */
    public GoogleUser exchangeCode(String code, String state) {
        if (!isConfigured()) {
            throw new OAuthNotConfiguredException();
        }
        if (!userRepository.consumeOAuthState(state)) {
            throw new GoogleOAuthException("Invalid or expired OAuth state");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri());
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        TokenResponse token;
        try {
            token = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (Exception e) {
            log.warn("Google token exchange failed: {}", e.getMessage());
            throw new GoogleOAuthException("Failed to exchange authorization code with Google", e);
        }
        if (token == null || token.accessToken() == null) {
            throw new GoogleOAuthException("Google did not return an access token");
        }

        try {
            GoogleUserInfo info = restClient.get()
                    .uri(USERINFO_ENDPOINT)
                    .header("Authorization", "Bearer " + token.accessToken())
                    .retrieve()
                    .body(GoogleUserInfo.class);
            if (info == null || info.sub() == null || info.email() == null) {
                throw new GoogleOAuthException("Google userinfo response is missing data");
            }
            return new GoogleUser(info.sub(), info.email().toLowerCase(),
                    Boolean.TRUE.equals(info.emailVerified()), info.name(), info.picture());
        } catch (GoogleOAuthException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google userinfo fetch failed: {}", e.getMessage());
            throw new GoogleOAuthException("Failed to fetch user profile from Google", e);
        }
    }

    private static String newState() {
        return UUID.randomUUID().toString().replace("-", "") + "-"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        new SecureRandom().generateSeed(9));
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record GoogleUser(String sub, String email, boolean emailVerified,
                             String name, String picture) {
    }

    private record TokenResponse(String accessToken, String idToken, String tokenType) {
    }

    private record GoogleUserInfo(String sub, String email, Boolean emailVerified,
                                  String name, String picture) {
    }
}