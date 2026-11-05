CREATE TABLE access_codes (
    id          uuid PRIMARY KEY,
    code_hash   varchar(64)  NOT NULL,
    email       varchar(320),
    max_uses    int          NOT NULL DEFAULT 1,
    used_count  int          NOT NULL DEFAULT 0,
    expires_at  timestamptz,
    revoked_at  timestamptz,
    note        varchar(255),
    created_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT access_codes_max_uses_check CHECK (max_uses > 0),
    CONSTRAINT access_codes_used_count_check CHECK (used_count >= 0)
);
CREATE UNIQUE INDEX uq_access_codes_code_hash ON access_codes (code_hash);

CREATE TABLE waitlist_entries (
    id              uuid PRIMARY KEY,
    email           varchar(320) NOT NULL,
    locale          varchar(10)  NOT NULL,
    status          varchar(20)  NOT NULL,
    access_code_id  uuid REFERENCES access_codes (id) ON DELETE SET NULL,
    created_at      timestamptz  NOT NULL,
    decided_at      timestamptz,
    CONSTRAINT waitlist_entries_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX uq_waitlist_entries_email ON waitlist_entries (lower(email));
