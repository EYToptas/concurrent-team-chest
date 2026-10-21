package io.github.eytoptas.teamchest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTests {

    private static final String NOW = "2026-10-18 00:00:00";

    @Autowired
    private JdbcTemplate jdbc;

    private long campaignId;

    @BeforeEach
    void createCampaign() {
        campaignId = insertCampaign("schema-test", 30, 300);
    }

    @Test
    void migrationCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables).contains(
                "campaign", "team", "team_assignment_cursor", "team_member", "idempotency_record");
    }

    @Test
    void teamLimitsMustEqualItsCampaignLimits() {
        assertViolates("fk_team_campaign_limits", () -> insertTeam(campaignId, 31, 300));
        assertViolates("fk_team_campaign_limits", () -> insertTeam(campaignId, 30, 500));
    }

    @Test
    void teamCannotExceedItsCapacity() {
        long teamId = insertTeam(campaignId, 30, 300);

        jdbc.update("UPDATE team SET member_count = 30 WHERE id = ?", teamId);

        assertViolates("ck_team_members",
                () -> jdbc.update("UPDATE team SET member_count = 31 WHERE id = ?", teamId));
    }

    @Test
    void teamPointsCannotExceedTargetAndCompletionMustMatchPoints() {
        long teamId = insertTeam(campaignId, 30, 300);

        assertViolates("ck_team_",
                () -> jdbc.update("UPDATE team SET total_points = 301 WHERE id = ?", teamId));
        assertViolates("ck_team_completed",
                () -> jdbc.update("UPDATE team SET total_points = 300 WHERE id = ?", teamId));
        assertViolates("ck_team_completed",
                () -> jdbc.update("UPDATE team SET total_points = 299, completed_at = ? WHERE id = ?", NOW, teamId));

        jdbc.update("UPDATE team SET total_points = 300, completed_at = ? WHERE id = ?", NOW, teamId);
    }

    @Test
    void campaignLimitsCannotChangeOnceTeamsExist() {
        insertTeam(campaignId, 30, 300);

        assertViolates("fk_team_campaign_limits",
                () -> jdbc.update("UPDATE campaign SET team_capacity = 40 WHERE id = ?", campaignId));
    }

    @Test
    void memberMustBelongToATeamOfTheSameCampaign() {
        long otherCampaignId = insertCampaign("schema-test-other", 30, 300);
        long teamOfOtherCampaign = insertTeam(otherCampaignId, 30, 300);

        assertViolates("fk_member_team", () -> insertMember(campaignId, teamOfOtherCampaign, "bob"));
    }

    @Test
    void memberIndexesLeaveOutTheColumnsThatProgressChanges() {
        Map<String, String> columnsByIndex = jdbc.queryForList("""
                        SELECT index_name AS name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'team_member'
                        GROUP BY index_name
                        """).stream()
                .collect(Collectors.toMap(row -> (String) row.get("name"), row -> (String) row.get("cols")));

        assertThat(columnsByIndex).containsOnly(
                entry("PRIMARY", "id"),
                entry("uk_member_campaign_user", "campaign_id,user_id"),
                entry("ix_member_team", "team_id,campaign_id"));
        assertThat(jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL
                        """, String.class))
                .contains("1", "2");
    }

    @Test
    void userCanJoinAnCampaignOnlyOnceAndUserIdsAreCaseSensitive() {
        long teamId = insertTeam(campaignId, 30, 300);
        insertMember(campaignId, teamId, "alice");

        assertViolates("uk_member_campaign_user", () -> insertMember(campaignId, teamId, "alice"));
        insertMember(campaignId, teamId, "Alice");
    }

    @Test
    void cursorCanOnlyPointToATeamOfItsOwnCampaign() {
        jdbc.update("INSERT INTO team_assignment_cursor (campaign_id) VALUES (?)", campaignId);
        long otherCampaignId = insertCampaign("schema-test-other", 30, 300);
        long teamOfOtherCampaign = insertTeam(otherCampaignId, 30, 300);

        assertViolates("fk_cursor_team", () -> jdbc.update(
                "UPDATE team_assignment_cursor SET filling_team_id = ? WHERE campaign_id = ?",
                teamOfOtherCampaign, campaignId));
    }

    @Test
    void idempotencyKeyIsUniquePerUserAndOperation() {
        insertIdempotencyRecord("alice", "PROGRESS", "key-1");

        assertViolates("uk_idempotency", () -> insertIdempotencyRecord("alice", "PROGRESS", "key-1"));
        insertIdempotencyRecord("alice", "CLAIM", "key-1");
        insertIdempotencyRecord("bob", "PROGRESS", "key-1");
    }

    @Test
    void idempotencyResultCodeAndPayloadAreSetTogether() {
        insertIdempotencyRecord("alice", "PROGRESS", "key-1");

        assertViolates("ck_idempotency_result", () -> jdbc.update(
                "UPDATE idempotency_record SET result_code = 'SUCCESS' WHERE idempotency_key = 'key-1'"));
    }

    private void assertViolates(String constraintName, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(constraintName);
    }

    private long insertCampaign(String code, int capacity, int targetPoints) {
        jdbc.update("""
                INSERT INTO campaign (code, team_capacity, target_points, reward, starts_at, ends_at)
                VALUES (?, ?, ?, '{"coins": 500}', '2026-01-01', '2027-01-01')
                """, code, capacity, targetPoints);
        return lastInsertId();
    }

    private long insertTeam(long campaignId, int capacity, int targetPoints) {
        jdbc.update("""
                INSERT INTO team (campaign_id, capacity, target_points, created_at)
                VALUES (?, ?, ?, ?)
                """, campaignId, capacity, targetPoints, NOW);
        return lastInsertId();
    }

    private void insertMember(long campaignId, long teamId, String userId) {
        jdbc.update("""
                INSERT INTO team_member (campaign_id, team_id, user_id, points_reached_seq, joined_at)
                VALUES (?, ?, ?, 0, ?)
                """, campaignId, teamId, userId, NOW);
    }

    private void insertIdempotencyRecord(String userId, String operation, String key) {
        jdbc.update("""
                INSERT INTO idempotency_record
                    (campaign_id, user_id, operation, idempotency_key, request_hash, created_at)
                VALUES (?, ?, ?, ?, REPEAT('a', 64), ?)
                """, campaignId, userId, operation, key, NOW);
    }

    private long lastInsertId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
