package com.aitp.orenda.auth;

import com.aitp.orenda.auth.dto.ApiError;
import com.aitp.orenda.auth.exception.EmailAlreadyExistsException;
import com.aitp.orenda.auth.exception.EmailNotVerifiedException;
import com.aitp.orenda.auth.exception.GoogleOAuthException;
import com.aitp.orenda.auth.exception.InvalidCredentialsException;
import com.aitp.orenda.auth.exception.InvalidVerificationTokenException;
import com.aitp.orenda.auth.exception.OAuthNotConfiguredException;
import com.aitp.orenda.auth.exception.UserNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiError> emailAlreadyExists(EmailAlreadyExistsException e) {
        return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e.getMessage());
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiError> emailNotVerified(EmailNotVerifiedException e) {
        return error(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", e.getMessage());
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ApiError> invalidVerificationToken(InvalidVerificationTokenException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN", e.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> userNotFound(UserNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(OAuthNotConfiguredException.class)
    public ResponseEntity<ApiError> oauthNotConfigured(OAuthNotConfiguredException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_NOT_CONFIGURED", e.getMessage());
    }

    @ExceptionHandler(GoogleOAuthException.class)
    public ResponseEntity<ApiError> googleOAuth(GoogleOAuthException e) {
        log.warn("Google OAuth failure: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "GOOGLE_OAUTH_ERROR", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, message));
    }
}