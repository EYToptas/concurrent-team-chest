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
                 + IF(m.user_id BETWEEN 'user-000001' AND CONCAT('user-', LPAD(@completed_teams * 30, 6, '0')), 10, 0);
SELECT 'claim_outcome_violations',
       ABS((SELECT COUNT(*) FROM team_member WHERE claimed_at IS NOT NULL)
           - (SELECT COUNT(*) FROM idempotency_record WHERE operation = 'CLAIM' AND result_code = 'SUCCESS'));
SELECT 'unfinished_outcome_violations', COUNT(*) FROM idempotency_record WHERE result_code IS NULL;

SELECT 'teams', COUNT(*) FROM team;
SELECT 'completed_teams', COUNT(*) FROM team WHERE completed_at IS NOT NULL;
SELECT 'members', COUNT(*) FROM team_member;
SELECT 'joined_members', COUNT(*) - @teams * 30 FROM team_member;
SELECT 'claimed_members', COUNT(*) FROM team_member WHERE claimed_at IS NOT NULL;
SELECT 'total_points', COALESCE(SUM(total_points), 0) FROM team;
SELECT CONCAT('outcomes.', LOWER(operation), '.', LOWER(result_code)), COUNT(*)
FROM idempotency_record
GROUP BY operation, result_code
ORDER BY operation, result_code;
