-- Keep the original migration immutable for databases that already applied it.
-- Existing out-of-range rows fail this migration; do not silently clamp user data.
ALTER TABLE research_project
    ADD CONSTRAINT ck_project_lead_range CHECK (lead_time_days BETWEEN 0 AND 182);
ALTER TABLE research_project
    MODIFY COLUMN submission_type VARCHAR(100) NULL;
