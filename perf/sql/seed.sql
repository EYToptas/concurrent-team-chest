-- Load-test data.
USE teamchest;

SET SESSION cte_max_recursion_depth = GREATEST(1000, @teams + 1);
SET @now = UTC_TIMESTAMP(6);

START TRANSACTION;

INSERT INTO campaign (code, team_capacity, target_points, reward, starts_at, ends_at)
VALUES ('perf', 30, 300, JSON_OBJECT('coins', 500), @now - INTERVAL 1 HOUR, @now + INTERVAL 1 DAY);
SET @campaign_id = LAST_INSERT_ID();

INSERT INTO team_assignment_cursor (campaign_id, filling_team_id) VALUES (@campaign_id, NULL);

INSERT INTO team (campaign_id, capacity, target_points, member_count, total_points, state_seq, completed_at, created_at)
WITH RECURSIVE team_no (n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM team_no WHERE n < @teams
)
SELECT @campaign_id, 30, 300, 30,
       IF(n <= @completed_teams, 300, 0),
       IF(n <= @completed_teams, 60, 30),
       IF(n <= @completed_teams, @now, NULL),
       @now
FROM team_no
ORDER BY n;

INSERT INTO team_member (campaign_id, team_id, user_id, points, points_reached_seq, joined_at, claimed_at)
WITH RECURSIVE seat (s) AS (
    SELECT 1 UNION ALL SELECT s + 1 FROM seat WHERE s < 30
),
ordered_team AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS t, completed_at IS NOT NULL AS completed
    FROM team
    WHERE campaign_id = @campaign_id
)
SELECT @campaign_id, ordered_team.id,
       CONCAT('user-', LPAD((ordered_team.t - 1) * 30 + seat.s, 6, '0')),
       IF(ordered_team.completed, 10, 0),
       IF(ordered_team.completed, 30 + seat.s, seat.s),
       @now, NULL
FROM ordered_team
CROSS JOIN seat
ORDER BY ordered_team.t, seat.s;

COMMIT;

SELECT 'campaign_id', @campaign_id;
SELECT 'teams', COUNT(*) FROM team WHERE campaign_id = @campaign_id;
SELECT 'completed_teams', COUNT(*) FROM team WHERE campaign_id = @campaign_id AND completed_at IS NOT NULL;
SELECT 'members', COUNT(*) FROM team_member WHERE campaign_id = @campaign_id;
SELECT 'points', COALESCE(SUM(total_points), 0) FROM team WHERE campaign_id = @campaign_id;
SELECT 'last_user', MAX(user_id) FROM team_member WHERE campaign_id = @campaign_id;
