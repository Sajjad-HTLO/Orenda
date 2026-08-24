package com.aitp.orenda.auth;

import com.aitp.orenda.auth.dto.AuthResponse;
import com.aitp.orenda.auth.dto.LoginRequest;
import com.aitp.orenda.auth.dto.SignupRequest;
import com.aitp.orenda.auth.dto.UserResponse;
import com.aitp.orenda.auth.exception.EmailAlreadyExistsException;
import com.aitp.orenda.auth.exception.EmailNotVerifiedException;
import com.aitp.orenda.auth.exception.InvalidCredentialsException;
import com.aitp.orenda.auth.exception.InvalidVerificationTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserRepository userRepository;
    private EmailService emailService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.generateToken(any(), anyString())).thenReturn("jwt-token");
        authService = new AuthService(userRepository, new BCryptPasswordEncoder(),
                jwtService, emailService);
    }

    private UserEntity localUser(boolean verified) {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash(new BCryptPasswordEncoder().encode("secret123"))
                .emailVerified(verified)
                .authProvider(AuthProvider.LOCAL)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void signup_createsUser_hashesPassword_andSendsEmail() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        UserResponse response = authService.signup(
                new SignupRequest("new@example.com", "secret123", "Test User"));

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.emailVerified()).isFalse();
        assertThat(response.authProvider()).isEqualTo(AuthProvider.LOCAL);

        verify(userRepository).insert(any(UserEntity.class));
        verify(emailService).sendVerificationEmail(eq("new@example.com"), anyString());
    }

    @Test
    void signup_rejectsDuplicateEmail() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(localUser(true)));

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("taken@example.com", "secret123", null)))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).insert(any());
    }

    @Test
    void login_returnsToken_forVerifiedUser() {
        UserEntity user = localUser(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        AuthResponse auth = authService.login(new LoginRequest("user@example.com", "secret123"));

        assertThat(auth.token()).isEqualTo("jwt-token");
        assertThat(auth.user().email()).isEqualTo("user@example.com");
        verify(userRepository).touchLastLogin(user.getId());
    }

    @Test
    void login_rejectsWrongPassword() {
        when(userRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.of(localUser(true)));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_rejectsUnverifiedEmail() {
        when(userRepository.findByEmail("user@example.com"))
                .thenReturn(Optional.of(localUser(false)));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("user@example.com", "secret123")))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void login_rejectsUnknownEmail() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("ghost@example.com", "secret123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void verifyEmail_marksVerified_forValidToken() {
        UserEntity user = localUser(false);
        user.setVerificationToken("valid-token");
        user.setVerificationTokenExpiresAt(Instant.now().plusSeconds(3600));
        when(userRepository.findByVerificationToken("valid-token")).thenReturn(Optional.of(user));

        UserResponse response = authService.verifyEmail("valid-token");

        assertThat(response.emailVerified()).isTrue();
        verify(userRepository).markEmailVerified(user.getId());
    }

    @Test
    void verifyEmail_rejectsUnknownToken() {
        when(userRepository.findByVerificationToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail("nope"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verifyEmail_rejectsExpiredToken() {
        UserEntity user = localUser(false);
        user.setVerificationToken("expired");
        user.setVerificationTokenExpiresAt(Instant.now().minusSeconds(10));
        when(userRepository.findByVerificationToken("expired")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyEmail("expired"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        verify(userRepository, never()).markEmailVerified(any());
    }

    @Test
    void googleLogin_createsAccount_forFirstTimeUser() {
        when(userRepository.findByGoogleSub("google-sub-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("gmail@example.com")).thenReturn(Optional.empty());

        GoogleOAuthService.GoogleUser googleUser = new GoogleOAuthService.GoogleUser(
                "google-sub-1", "Gmail@Example.com", true, "Gmail User", "http://pic");

        AuthResponse auth = authService.googleLogin(googleUser);

        assertThat(auth.user().email()).isEqualTo("gmail@example.com");
        assertThat(auth.user().emailVerified()).isTrue();
        assertThat(auth.user().authProvider()).isEqualTo(AuthProvider.GOOGLE);
    }

    @Test
    void googleLogin_linksExistingLocalAccount() {
        UserEntity existing = localUser(false);
        when(userRepository.findByGoogleSub("google-sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(existing));

        GoogleOAuthService.GoogleUser googleUser = new GoogleOAuthService.GoogleUser(
                "google-sub-2", "local@example.com", true, "Local", null);

        AuthResponse auth = authService.googleLogin(googleUser);

        assertThat(auth.token()).isEqualTo("jwt-token");
        verify(userRepository).linkGoogleSub(existing.getId(), "google-sub-2");
        verify(userRepository).markEmailVerified(existing.getId());
    }
}