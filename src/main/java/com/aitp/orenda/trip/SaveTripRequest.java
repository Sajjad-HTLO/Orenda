package com.aitp.orenda.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /api/trips — saves a freshly generated plan for the user.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SaveTripRequest {

    @Size(max = 200, message = "name must be at most 200 characters")
    private String name;

    @Size(max = 200, message = "destination must be at most 200 characters")
    private String destination;

    @Valid
    @NotNull(message = "plan must not be null")
    private TripPlanResponse plan;
}