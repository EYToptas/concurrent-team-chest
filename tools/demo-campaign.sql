-- Creates an active campaign (teams of 30, target 300 points) for trying the API by hand.
USE teamchest;

SET @now = UTC_TIMESTAMP(6);

INSERT INTO campaign (code, team_capacity, target_points, reward, starts_at, ends_at)
VALUES (CONCAT('demo-', DATE_FORMAT(@now, '%Y%m%d%H%i%s')), 30, 300, JSON_OBJECT('coins', 500),
        @now - INTERVAL 1 MINUTE, @now + INTERVAL 1 DAY);

INSERT INTO team_assignment_cursor (campaign_id, filling_team_id) VALUES (LAST_INSERT_ID(), NULL);

SELECT id AS campaign_id, code, starts_at, ends_at FROM campaign ORDER BY id DESC LIMIT 1;
