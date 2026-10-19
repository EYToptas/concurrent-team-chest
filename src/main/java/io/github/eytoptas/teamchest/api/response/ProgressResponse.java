package io.github.eytoptas.teamchest.api.response;

import io.github.eytoptas.teamchest.application.progress.ProgressResult;

public record ProgressResponse(long campaignId, long teamId, int requestedPoints, int appliedPoints, int userPoints,
                               int teamPoints, int targetPoints, boolean teamCompleted, long version) {

    public static ProgressResponse from(ProgressResult.Applied applied) {
        return new ProgressResponse(applied.campaignId(), applied.teamId(), applied.requestedPoints(),
                applied.appliedPoints(), applied.userPoints(), applied.teamPoints(), applied.targetPoints(),
                applied.teamCompleted(), applied.version());
    }
}
