-- Initial schema.
CREATE TABLE campaign (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(64)  NOT NULL,
    team_capacity SMALLINT     NOT NULL,
    target_points INT          NOT NULL,
    reward        JSON         NOT NULL,
    starts_at     DATETIME(6)  NOT NULL COMMENT 'UTC',
    ends_at       DATETIME(6)  NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_campaign_code UNIQUE (code),
    CONSTRAINT uk_campaign_limits UNIQUE (id, team_capacity, target_points),
    CONSTRAINT ck_campaign_capacity CHECK (team_capacity > 0),
    CONSTRAINT ck_campaign_target CHECK (target_points > 0),
    CONSTRAINT ck_campaign_window CHECK (ends_at > starts_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE team (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT      NOT NULL,
    capacity      SMALLINT    NOT NULL,
    target_points INT         NOT NULL,
    member_count  SMALLINT    NOT NULL DEFAULT 0,
    total_points  INT         NOT NULL DEFAULT 0,
    state_seq     BIGINT      NOT NULL DEFAULT 0,
    completed_at  DATETIME(6) NULL COMMENT 'UTC, set once when total_points reaches target_points',
    created_at    DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_team_id_campaign UNIQUE (id, campaign_id),
    CONSTRAINT fk_team_campaign_limits FOREIGN KEY (campaign_id, capacity, target_points)
        REFERENCES campaign (id, team_capacity, target_points),
    CONSTRAINT ck_team_members CHECK (member_count BETWEEN 0 AND capacity),
    CONSTRAINT ck_team_points CHECK (total_points BETWEEN 0 AND target_points),
    CONSTRAINT ck_team_state_seq CHECK (state_seq >= 0),
    CONSTRAINT ck_team_completed CHECK (
        (completed_at IS NULL AND total_points < target_points)
        OR (completed_at IS NOT NULL AND total_points = target_points)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE team_assignment_cursor (
    campaign_id     BIGINT NOT NULL,
    filling_team_id BIGINT NULL,
    PRIMARY KEY (campaign_id),
    CONSTRAINT fk_cursor_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT fk_cursor_team FOREIGN KEY (filling_team_id, campaign_id) REFERENCES team (id, campaign_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE team_member (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id        BIGINT      NOT NULL,
    team_id            BIGINT      NOT NULL,
    user_id            VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    points             INT         NOT NULL DEFAULT 0,
    points_reached_seq BIGINT      NOT NULL,
    joined_at          DATETIME(6) NOT NULL COMMENT 'UTC',
    claimed_at         DATETIME(6) NULL COMMENT 'UTC, set once when the chest reward is claimed',
    PRIMARY KEY (id),
    CONSTRAINT uk_member_campaign_user UNIQUE (campaign_id, user_id),
    INDEX ix_member_ranking (team_id, campaign_id, points DESC, points_reached_seq ASC),
    CONSTRAINT fk_member_team FOREIGN KEY (team_id, campaign_id) REFERENCES team (id, campaign_id),
    CONSTRAINT ck_member_points CHECK (points >= 0),
    CONSTRAINT ck_member_reached_seq CHECK (points_reached_seq >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_record (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id     BIGINT      NOT NULL,
    user_id         VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation       VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash    CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 hex',
    result_code     VARCHAR(32) NULL,
    result_payload  JSON        NULL,
    created_at      DATETIME(6) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency UNIQUE (user_id, operation, idempotency_key),
    INDEX ix_idempotency_campaign (campaign_id),
    CONSTRAINT fk_idempotency_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT ck_idempotency_operation CHECK (operation IN ('PROGRESS', 'CLAIM')),
    CONSTRAINT ck_idempotency_result CHECK ((result_code IS NULL) = (result_payload IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
