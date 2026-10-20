CREATE TABLE application_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    application_name VARCHAR(100) NOT NULL,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO application_metadata (id, application_name)
VALUES (1, 'TradeLab Portfolio');
