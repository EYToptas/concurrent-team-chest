package io.github.eytoptas.teamchest.application;

import io.github.eytoptas.teamchest.application.cache.TeamStateChanged;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class AfterCommitRecorder {

    public record Observation(TeamStateChanged campaign, long committedVersion) {
    }

    private final DataSource dataSource;
    private final List<Observation> observations = new CopyOnWriteArrayList<>();

    public AfterCommitRecorder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamStateChanged(TeamStateChanged campaign) {
        observations.add(new Observation(campaign, committedVersionOf(campaign.teamId())));
    }

    public List<Observation> forTeam(long teamId) {
        return observations.stream().filter(observation -> observation.campaign().teamId() == teamId).toList();
    }

    private long committedVersionOf(long teamId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT state_seq FROM team WHERE id = ?")) {
            statement.setLong(1, teamId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
