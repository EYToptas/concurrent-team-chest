package io.github.eytoptas.teamchest.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ClaimRequest(@NotNull @Positive Long campaignId) {
}
