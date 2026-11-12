ALTER TABLE teams ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE teams ADD CONSTRAINT team_name_not_blank CHECK(length(trim(name)) BETWEEN 1 AND 100);
