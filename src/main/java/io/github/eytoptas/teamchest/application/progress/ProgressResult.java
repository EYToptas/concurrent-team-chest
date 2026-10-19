package io.github.eytoptas.teamchest.application.progress;

public sealed interface ProgressResult {

    record Applied(long campaignId, long teamId, int requestedPoints, int appliedPoints, int userPoints,
                   int teamPoints, int targetPoints, boolean teamCompleted, long version) implements ProgressResult {
    }

    record TeamAlreadyCompleted(long campaignId, long teamId, int targetPoints) implements ProgressResult {
    }

    record NotAMember(long campaignId) implements ProgressResult {
    }
}
