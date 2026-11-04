CREATE TABLE IF NOT EXISTS {{table:muted}} (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    player_name VARCHAR(64) NOT NULL
);
