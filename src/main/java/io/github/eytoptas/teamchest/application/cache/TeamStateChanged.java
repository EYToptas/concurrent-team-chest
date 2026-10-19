package io.github.eytoptas.teamchest.application.cache;

public record TeamStateChanged(long campaignId, long teamId, long version) {
}
