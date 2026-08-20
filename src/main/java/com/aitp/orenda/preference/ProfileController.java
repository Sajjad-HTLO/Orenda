package com.aitp.orenda.preference;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Short onboarding for the long-term traveler profile.
 * <p>
 * <ul>
 *   <li>POST /api/profile          — upsert the traveler's baseline profile</li>
 *   <li>GET  /api/profile/{sessionId} — retrieve it</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final PreferenceService preferenceService;

    @PostMapping
    public ResponseEntity<TravelerProfileResponse> upsert(
            @Valid @RequestBody TravelerProfileRequest request) {
        return ResponseEntity.ok(preferenceService.upsertProfile(request));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<TravelerProfileResponse> get(@PathVariable String sessionId) {
        TravelerProfileResponse profile = preferenceService.getProfile(sessionId);
        return profile == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(profile);
    }
}