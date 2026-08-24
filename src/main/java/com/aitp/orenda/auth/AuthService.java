package com.aitp.orenda.auth;

import com.aitp.orenda.auth.dto.AuthResponse;
import com.aitp.orenda.auth.dto.LoginRequest;
import com.aitp.orenda.auth.dto.ResendVerificationRequest;
import com.aitp.orenda.auth.dto.SignupRequest;
import com.aitp.orenda.auth.dto.UserResponse;
import com.aitp.orenda.auth.exception.EmailAlreadyExistsException;
import com.aitp.orenda.auth.exception.EmailNotVerifiedException;
import com.aitp.orenda.auth.exception.InvalidCredentialsException;
import com.aitp.orenda.auth.exception.InvalidVerificationTokenException;
import com.aitp.orenda.auth.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;

    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }

        String token = generateVerificationToken();
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName() == null ? null : request.fullName().trim())
                .emailVerified(false)
                .authProvider(AuthProvider.LOCAL)
                .verificationToken(token)
                .verificationTokenExpiresAt(Instant.now().plus(VERIFICATION_TOKEN_TTL))
                .build();

        userRepository.insert(user);
        emailService.sendVerificationEmail(email, token);
        log.info("User signed up: {} (id={})", email, user.getId());
        return toResponse(user);
    }

    @Transactional
    public UserResponse verifyEmail(String token) {
        UserEntity user = userRepository.findByVerificationToken(token)
                .orElseThrow(InvalidVerificationTokenException::new);

        if (user.getVerificationTokenExpiresAt() == null
                || user.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new InvalidVerificationTokenException();
        }

        userRepository.markEmailVerified(user.getId());
        log.info("Email verified for {}", user.getEmail());
        user.setEmailVerified(true);
        return toResponse(user);
    }

    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        String email = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        if (user.isEmailVerified()) {
            return;
        }

        String token = generateVerificationToken();
        userRepository.updateVerificationToken(user.getId(), token,
                Instant.now().plus(VERIFICATION_TOKEN_TTL));
        emailService.sendVerificationEmail(email, token);
        log.info("Verification email re-sent to {}", email);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getAuthProvider() == AuthProvider.GOOGLE) {
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        return issueToken(user);
    }

    @Transactional
    public AuthResponse googleLogin(GoogleOAuthService.GoogleUser googleUser) {
        String email = googleUser.email().trim().toLowerCase();
        UserEntity user = userRepository.findByGoogleSub(googleUser.sub())
                .or(() -> userRepository.findByEmail(email))
                .orElse(null);

        if (user == null) {
            user = UserEntity.builder()
                    .email(email)
                    .fullName(googleUser.name())
                    .emailVerified(true)
                    .authProvider(AuthProvider.GOOGLE)
                    .googleSub(googleUser.sub())
                    .build();
            userRepository.insert(user);
            log.info("New Google user: {} (id={})", user.getEmail(), user.getId());
        } else if (user.getGoogleSub() == null) {
            // First Google login for an existing local account: link it.
            userRepository.linkGoogleSub(user.getId(), googleUser.sub());
            if (!user.isEmailVerified()) {
                userRepository.markEmailVerified(user.getId());
            }
            user.setGoogleSub(googleUser.sub());
            user.setEmailVerified(true);
            log.info("Linked Google identity to existing account {}", user.getEmail());
        }

        return issueToken(user);
    }

    private AuthResponse issueToken(UserEntity user) {
        userRepository.touchLastLogin(user.getId());
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return AuthResponse.of(token, toResponse(user));
    }

    private String generateVerificationToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    public static UserResponse toResponse(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.isEmailVerified(),
                user.getAuthProvider(),
                user.getCreatedAt());
    }
}