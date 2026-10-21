-- Member team index without the ranking columns.
ALTER TABLE team_member
    ADD INDEX ix_member_team (team_id, campaign_id),
    DROP INDEX ix_member_ranking,
    ALGORITHM = INPLACE,
    LOCK = NONE;
