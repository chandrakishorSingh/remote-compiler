CREATE TABLE executions (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    language    VARCHAR(32) NOT NULL,
    code        TEXT        NOT NULL,
    status      VARCHAR(32) NOT NULL,
    stdout      TEXT        NOT NULL,
    stderr      TEXT        NOT NULL,
    exit_code   INTEGER,
    duration_ms BIGINT      NOT NULL,
    truncated   BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
