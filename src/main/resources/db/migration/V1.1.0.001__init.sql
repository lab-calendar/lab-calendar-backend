-- KAN-28. Never edit an applied migration; add V1.1.0.002__description.sql.
-- tableOptions selects MySQL engine/charset; H2 tests omit only these physical options.
CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_category_code UNIQUE (code),
    CONSTRAINT ck_category_code CHECK (code IN ('project', 'lab', 'card')),
    CONSTRAINT ck_category_sort CHECK (sort_order >= 0)
) ${tableOptions};

CREATE TABLE member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ${tableOptions};

CREATE TABLE research_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    submission_type VARCHAR(100) NOT NULL,
    end_date DATE NOT NULL,
    lead_time_days INT NOT NULL DEFAULT 21,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_project_lead_time CHECK (lead_time_days >= 0)
) ${tableOptions};
CREATE INDEX ix_project_active_end ON research_project (active, end_date);

CREATE TABLE card_expense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_document_id VARCHAR(191) NOT NULL,
    source_record_id VARCHAR(191) NOT NULL,
    used_on DATE NOT NULL,
    card_name VARCHAR(100) NOT NULL,
    purpose TEXT NOT NULL,
    usage_type VARCHAR(50) NOT NULL,
    participant_names_raw TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_card_source UNIQUE (source_document_id, source_record_id)
) ${tableOptions};

CREATE TABLE event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    owner_member_id BIGINT,
    research_project_id BIGINT,
    card_expense_id BIGINT,
    title VARCHAR(500) NOT NULL,
    memo TEXT,
    manual_detail TEXT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    all_day BOOLEAN NOT NULL DEFAULT TRUE,
    start_time TIME(0),
    end_time TIME(0),
    source VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_event_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_owner FOREIGN KEY (owner_member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_project FOREIGN KEY (research_project_id) REFERENCES research_project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_card FOREIGN KEY (card_expense_id) REFERENCES card_expense (id) ON DELETE RESTRICT,
    CONSTRAINT uq_event_project UNIQUE (research_project_id),
    CONSTRAINT uq_event_card UNIQUE (card_expense_id),
    CONSTRAINT ck_event_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_event_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_event_times CHECK (
        (all_day = TRUE AND start_time IS NULL AND end_time IS NULL)
        OR (all_day = FALSE AND start_time IS NOT NULL AND end_time IS NOT NULL
            AND (end_date > start_date OR end_time > start_time))
    ),
    CONSTRAINT ck_event_source CHECK (
        (source = 'MANUAL' AND research_project_id IS NULL AND card_expense_id IS NULL)
        OR (source = 'AUTO_GENERATED' AND research_project_id IS NOT NULL AND card_expense_id IS NULL)
        OR (source = 'GOOGLE_SYNC' AND research_project_id IS NULL AND card_expense_id IS NOT NULL)
    ),
    CONSTRAINT ck_event_detail CHECK (source = 'MANUAL' OR manual_detail IS NULL)
) ${tableOptions};
CREATE INDEX ix_event_dates ON event (start_date, end_date);
CREATE INDEX ix_event_category_dates ON event (category_id, start_date, end_date);
CREATE INDEX ix_event_owner ON event (owner_member_id);

CREATE TABLE event_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    member_id BIGINT,
    display_name VARCHAR(100) NOT NULL,
    position INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_participant_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_participant_member FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE RESTRICT,
    CONSTRAINT uq_participant_member UNIQUE (event_id, member_id),
    CONSTRAINT uq_participant_position UNIQUE (event_id, position),
    CONSTRAINT ck_participant_position CHECK (position >= 0)
) ${tableOptions};
CREATE INDEX ix_participant_member ON event_participant (member_id);

CREATE TABLE sync_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_document_id VARCHAR(191) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    duration_ms BIGINT,
    processed_count INT NOT NULL DEFAULT 0,
    created_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT ck_sync_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_sync_status CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_sync_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_sync_counts CHECK (processed_count >= 0 AND created_count >= 0 AND updated_count >= 0
        AND skipped_count >= 0 AND created_count + updated_count <= processed_count)
) ${tableOptions};
CREATE INDEX ix_sync_document_started ON sync_log (source_document_id, started_at, id);

CREATE TABLE sync_log_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sync_log_id BIGINT NOT NULL,
    source_locator VARCHAR(255) NOT NULL,
    source_record_id VARCHAR(191),
    error_code VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    CONSTRAINT fk_sync_error_log FOREIGN KEY (sync_log_id) REFERENCES sync_log (id) ON DELETE CASCADE
) ${tableOptions};
CREATE INDEX ix_sync_error_log ON sync_log_error (sync_log_id);

INSERT INTO category (code, name, sort_order, created_at, updated_at) VALUES
    ('project', '과제/연구 관리', 0, '2026-09-08 00:00:00', '2026-09-08 00:00:00'),
    ('lab', '랩실 주기적 일정', 1, '2026-09-08 00:00:00', '2026-09-08 00:00:00'),
    ('card', '카드/경비 사용', 2, '2026-09-08 00:00:00', '2026-09-08 00:00:00');
