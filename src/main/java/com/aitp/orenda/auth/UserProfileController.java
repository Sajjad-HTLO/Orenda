package com.aitp.orenda.auth;

import com.aitp.orenda.auth.dto.UpdateProfileRequest;
import com.aitp.orenda.auth.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated user's personal profile (display name, avatar, home city,
 * dietary restrictions).
 */
@RestController
@RequestMapping("/api/users/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserRepository userRepository;

    @GetMapping
    public UserResponse get(@AuthenticationPrincipal UserEntity user) {
        return AuthService.toResponse(user);
    }

    @PutMapping
    public UserResponse update(@Valid @RequestBody UpdateProfileRequest request,
                               @AuthenticationPrincipal UserEntity user) {
        String fullName = request.fullName() == null || request.fullName().isBlank()
                ? user.getFullName()
                : request.fullName().trim();
        String avatarUrl = request.avatarUrl() == null ? user.getAvatarUrl() : request.avatarUrl().trim();
        String homeCity = request.homeCity() == null ? user.getHomeCity() : request.homeCity().trim();
        String[] diets = request.dietaryRestrictions() == null
                ? user.getDietaryRestrictions()
                : request.dietaryRestrictions().stream().map(String::trim).toArray(String[]::new);

        userRepository.updateProfile(user.getId(), fullName, avatarUrl, homeCity, diets);

        user.setFullName(fullName);
        user.setAvatarUrl(avatarUrl);
        user.setHomeCity(homeCity);
        user.setDietaryRestrictions(diets);
        return AuthService.toResponse(user);
    }
}