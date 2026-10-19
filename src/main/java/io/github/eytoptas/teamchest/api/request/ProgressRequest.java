package io.github.eytoptas.teamchest.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProgressRequest(@NotNull @Positive Long campaignId, @NotNull @Positive Integer points) {
}
