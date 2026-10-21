package io.github.eytoptas.teamchest.domain.team;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

public final class MemberRanking {

    private MemberRanking() {
    }

    public static <T> Comparator<T> order(ToIntFunction<? super T> points, ToLongFunction<? super T> pointsReachedSeq) {
        Objects.requireNonNull(points, "points");
        Objects.requireNonNull(pointsReachedSeq, "pointsReachedSeq");
        Comparator<T> morePointsFirst = Comparator.<T>comparingInt(points).reversed();
        return morePointsFirst.thenComparingLong(pointsReachedSeq);
    }
}
