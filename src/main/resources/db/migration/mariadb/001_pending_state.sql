CREATE TABLE IF NOT EXISTS {{table:pending}} (
    `player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY,
    `player_name` VARCHAR(16) NOT NULL,
    `triggered_at` BIGINT NOT NULL,
    `state` LONGTEXT NOT NULL
);
