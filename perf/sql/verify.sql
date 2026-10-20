-- Invariant checks after a load test.
USE teamchest;

SELECT 'over_capacity_violations', COUNT(*) FROM team WHERE member_count > capacity;
SELECT 'over_target_violations', COUNT(*) FROM team WHERE total_points > target_points;

SELECT 'filling_team_violations', COUNT(*)
FROM (SELECT campaign_id FROM team WHERE member_count < capacity GROUP BY campaign_id HAVING COUNT(*) > 1) AS campaigns;
SELECT 'cursor_violations', COUNT(*)
FROM team_assignment_cursor c
WHERE NOT (c.filling_team_id <=> (SELECT MIN(t.id) FROM team t
                                   WHERE t.campaign_id = c.campaign_id AND t.member_count < t.capacity));

SELECT 'member_count_violations', COUNT(*)
FROM team t
WHERE t.member_count <> (SELECT COUNT(*) FROM team_member m WHERE m.team_id = t.id);
SELECT 'team_points_violations', COUNT(*)
FROM team t
WHERE t.total_points <> (SELECT COALESCE(SUM(m.points), 0) FROM team_member m WHERE m.team_id = t.id);

SELECT 'completion_violations', COUNT(*) FROM team WHERE (completed_at IS NOT NULL) <> (total_points = target_points);
SELECT 'claim_before_completion_violations', COUNT(*)
FROM team_member m JOIN team t ON t.id = m.team_id
WHERE m.claimed_at IS NOT NULL AND t.completed_at IS NULL;

SELECT 'member_points_violations', COUNT(*)
FROM team_member m
LEFT JOIN (SELECT user_id, SUM(CAST(result_payload ->> '$.appliedPoints' AS SIGNED)) AS applied
           FROM idempotency_record
           WHERE operation = 'PROGRESS' AND result_code = 'SUCCESS'
           GROUP BY user_id) AS progress ON progress.user_id = m.user_id
WHERE m.points <> COALESCE(progress.applied, 0)
                 + IF(m.user_id NOT LIKE 'user-%', 0,
                      CASE
                          WHEN (CAST(SUBSTRING(m.user_id, 6) AS UNSIGNED) - 1) DIV 30 < @completed_teams THEN 10
                          WHEN (CAST(SUBSTRING(m.user_id, 6) AS UNSIGNED) - 1) DIV 30 < @completed_teams + @finishing_teams
                              THEN IF((CAST(SUBSTRING(m.user_id, 6) AS UNSIGNED) - 1) % 30 < 16, 10, 9)
                          ELSE 0
                      END);
SELECT 'claim_outcome_violations',
       ABS((SELECT COUNT(*) FROM team_member WHERE claimed_at IS NOT NULL)
           - (SELECT COUNT(*) FROM idempotency_record WHERE operation = 'CLAIM' AND result_code = 'SUCCESS'));
SELECT 'unfinished_outcome_violations', COUNT(*) FROM idempotency_record WHERE result_code IS NULL;
SELECT 'double_claim_violations', COUNT(*)
FROM (SELECT user_id FROM idempotency_record WHERE operation = 'CLAIM' AND result_code = 'SUCCESS'
      GROUP BY user_id HAVING COUNT(*) > 1) AS users;
SELECT 'completion_outcome_violations', COUNT(*)
FROM (SELECT id, completed_at, ROW_NUMBER() OVER (ORDER BY id) AS team_no FROM team) AS t
LEFT JOIN (SELECT CAST(result_payload ->> '$.teamId' AS UNSIGNED) AS team_id, COUNT(*) AS n
           FROM idempotency_record
           WHERE operation = 'PROGRESS' AND result_code = 'SUCCESS' AND result_payload ->> '$.teamCompleted' = 'true'
           GROUP BY team_id) AS completing ON completing.team_id = t.id
WHERE COALESCE(completing.n, 0) <> IF(t.completed_at IS NOT NULL AND t.team_no > @completed_teams, 1, 0);

SELECT 'teams', COUNT(*) FROM team;
SELECT 'completed_teams', COUNT(*) FROM team WHERE completed_at IS NOT NULL;
SELECT 'completed_during_run', COUNT(*) - @completed_teams FROM team WHERE completed_at IS NOT NULL;
SELECT 'members', COUNT(*) FROM team_member;
SELECT 'joined_members', COUNT(*) - @teams * 30 FROM team_member;
SELECT 'claimed_members', COUNT(*) FROM team_member WHERE claimed_at IS NOT NULL;
SELECT 'total_points', COALESCE(SUM(total_points), 0) FROM team;
SELECT CONCAT('outcomes.', LOWER(operation), '.', LOWER(result_code)), COUNT(*)
FROM idempotency_record
GROUP BY operation, result_code
ORDER BY operation, result_code;
