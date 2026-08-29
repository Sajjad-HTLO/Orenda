package com.aitp.orenda.trip;

import com.aitp.orenda.auth.UserEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Saved-trip management for the authenticated user.
 * <ul>
 *   <li>GET    /api/trips              — list saved trips</li>
 *   <li>POST   /api/trips              — save a freshly generated plan</li>
 *   <li>GET    /api/trips/{id}         — full itinerary with days and stops</li>
 *   <li>PUT    /api/trips/{id}         — update stops / reorder / adjust notes</li>
 *   <li>PATCH  /api/trips/{id}         — alias of PUT</li>
 *   <li>DELETE /api/trips/{id}         — delete (or archive via ?archive=true)</li>
 *   <li>GET    /api/trips/{id}/export  — itinerary as PDF or iCal (.ics)</li>
 *   <li>POST   /api/trips/recalculate  — adjust remaining stops for a real-time event</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class SavedTripController {

    private static final Set<String> EXPORT_FORMATS = Set.of("ics", "pdf");

    private final SavedTripService savedTripService;
    private final TripExportService exportService;

    @GetMapping
    public List<SavedTripSummaryResponse> list(@AuthenticationPrincipal UserEntity user) {
        return savedTripService.list(requireUser(user).getId());
    }

    @PostMapping
    public ResponseEntity<SavedTripDetailResponse> save(@Valid @RequestBody SaveTripRequest request,
                                                        @AuthenticationPrincipal UserEntity user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedTripService.save(requireUser(user).getId(), request));
    }

    @GetMapping("/{id}")
    public SavedTripDetailResponse get(@PathVariable UUID id, @AuthenticationPrincipal UserEntity user) {
        return savedTripService.get(requireUser(user).getId(), id);
    }

    @PutMapping("/{id}")
    public SavedTripDetailResponse update(@PathVariable UUID id,
                                          @RequestBody UpdateTripRequest request,
                                          @AuthenticationPrincipal UserEntity user) {
        return savedTripService.update(requireUser(user).getId(), id, request);
    }

    @PatchMapping("/{id}")
    public SavedTripDetailResponse patch(@PathVariable UUID id,
                                         @RequestBody UpdateTripRequest request,
                                         @AuthenticationPrincipal UserEntity user) {
        return savedTripService.update(requireUser(user).getId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @RequestParam(defaultValue = "false") boolean archive,
                                       @AuthenticationPrincipal UserEntity user) {
        savedTripService.delete(requireUser(user).getId(), id, archive);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "ics") String format,
                                         @AuthenticationPrincipal UserEntity user) {
        String safeFormat = format.toLowerCase(Locale.ROOT);
        if (!EXPORT_FORMATS.contains(safeFormat)) {
            return ResponseEntity.badRequest().build();
        }
        SavedTrip trip = savedTripService.loadForExport(requireUser(user).getId(), id);

        byte[] body = "pdf".equals(safeFormat)
                ? exportService.toPdf(trip)
                : exportService.encode(exportService.toIcs(trip));
        MediaType contentType = "pdf".equals(safeFormat)
                ? MediaType.APPLICATION_PDF
                : new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportService.filename(trip, safeFormat) + "\"")
                .body(body);
    }

    @PostMapping("/recalculate")
    public SavedTripDetailResponse recalculate(@Valid @RequestBody RecalculateTripRequest request,
                                               @AuthenticationPrincipal UserEntity user) {
        return savedTripService.recalculate(requireUser(user).getId(), request);
    }

    private UserEntity requireUser(UserEntity user) {
        if (user == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }
}