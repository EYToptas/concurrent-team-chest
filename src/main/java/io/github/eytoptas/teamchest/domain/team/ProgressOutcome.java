package io.github.eytoptas.teamchest.domain.team;

public sealed interface ProgressOutcome {

    record Applied(int requestedPoints, int appliedPoints, int memberPoints,
                   int teamPoints, int targetPoints, boolean teamCompleted) implements ProgressOutcome {
    }

    record TeamAlreadyCompleted(int teamPoints, int targetPoints) implements ProgressOutcome {
    }
}
