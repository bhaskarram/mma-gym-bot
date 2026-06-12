-- MMA Gym Bot — MySQL Schema
-- Run once on a fresh database: mysql -u root -p mmagym < schema.sql

CREATE DATABASE IF NOT EXISTS mmagym CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mmagym;

CREATE TABLE IF NOT EXISTS members (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100) NOT NULL,
    phone             VARCHAR(20)  NOT NULL,
    dob               DATE,
    weight_kg         DECIMAL(5,2),
    join_date         DATE,
    plan_type         ENUM('MONTHLY','QUARTERLY','ANNUAL') NOT NULL,
    plan_expiry       DATE,
    payment_status    ENUM('PAID','UNPAID','EXPIRED')      NOT NULL DEFAULT 'UNPAID',
    status            ENUM('ACTIVE','INACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
    emergency_contact VARCHAR(200),
    injuries          TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_phone (phone)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS attendance (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    member_id   BIGINT      NOT NULL,
    class_date  DATE        NOT NULL,
    status      ENUM('PRESENT','ABSENT','LATE') NOT NULL DEFAULT 'PRESENT',
    marked_by   ENUM('SELF','ADMIN','QR')       NOT NULL DEFAULT 'SELF',
    class_type  VARCHAR(50),
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_date (member_id, class_date),
    CONSTRAINT fk_attendance_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS progress (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    member_id   BIGINT         NOT NULL,
    log_date    DATE           NOT NULL,
    weight_kg   DECIMAL(5,2),
    metric_type VARCHAR(50),
    value       DECIMAL(8,2),
    notes       TEXT,
    PRIMARY KEY (id),
    CONSTRAINT fk_progress_member FOREIGN KEY (member_id) REFERENCES members (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS gym_classes (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    class_name    VARCHAR(100) NOT NULL,
    day_of_week   TINYINT      NOT NULL COMMENT '1=Monday 7=Sunday',
    class_time    TIME         NOT NULL,
    class_end_time TIME        NOT NULL,
    max_capacity  INT          NOT NULL DEFAULT 20,
    coach         VARCHAR(100),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Class schedule
INSERT IGNORE INTO gym_classes (class_name, day_of_week, class_time, class_end_time, coach) VALUES
    -- Monday: Conditioning
    ('Monday Conditioning',   1, '06:00:00', '07:30:00', 'Coach'),
    ('Monday Conditioning',   1, '07:30:00', '09:00:00', 'Coach'),
    ('Monday Conditioning',   1, '18:30:00', '20:00:00', 'Coach'),
    ('Monday Conditioning',   1, '20:00:00', '21:30:00', 'Coach'),
    -- Tuesday: Kicks
    ('Tuesday Kicks',         2, '06:00:00', '07:30:00', 'Coach'),
    ('Tuesday Kicks',         2, '07:30:00', '09:00:00', 'Coach'),
    ('Tuesday Kicks',         2, '18:30:00', '20:00:00', 'Coach'),
    ('Tuesday Kicks',         2, '20:00:00', '21:30:00', 'Coach'),
    -- Wednesday: Punches
    ('Wednesday Punches',     3, '06:00:00', '07:30:00', 'Coach'),
    ('Wednesday Punches',     3, '07:30:00', '09:00:00', 'Coach'),
    ('Wednesday Punches',     3, '18:30:00', '20:00:00', 'Coach'),
    ('Wednesday Punches',     3, '20:00:00', '21:30:00', 'Coach'),
    -- Thursday: Muay Thai
    ('Thursday Muay Thai',    4, '06:00:00', '07:30:00', 'Coach'),
    ('Thursday Muay Thai',    4, '07:30:00', '09:00:00', 'Coach'),
    ('Thursday Muay Thai',    4, '18:30:00', '20:00:00', 'Coach'),
    ('Thursday Muay Thai',    4, '20:00:00', '21:30:00', 'Coach'),
    -- Friday: Pad Work
    ('Friday Pad Work',       5, '06:00:00', '07:30:00', 'Coach'),
    ('Friday Pad Work',       5, '07:30:00', '09:00:00', 'Coach'),
    ('Friday Pad Work',       5, '18:30:00', '20:00:00', 'Coach'),
    ('Friday Pad Work',       5, '20:00:00', '21:30:00', 'Coach'),
    -- Saturday: Sparring
    ('Saturday Sparring',     6, '06:00:00', '07:30:00', 'Coach'),
    ('Saturday Sparring',     6, '07:30:00', '09:00:00', 'Coach'),
    ('Saturday Sparring',     6, '18:30:00', '20:00:00', 'Coach'),
    ('Saturday Sparring',     6, '20:00:00', '21:30:00', 'Coach');
    -- Sunday (day_of_week=7) is intentionally excluded — rest day, no classes.

-- Engagement features (Phase 4)
ALTER TABLE members ADD COLUMN IF NOT EXISTS streak_count INT NOT NULL DEFAULT 0;
ALTER TABLE members ADD COLUMN IF NOT EXISTS streak_shield BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE members ADD COLUMN IF NOT EXISTS last_checkin_date DATE;
ALTER TABLE members ADD COLUMN IF NOT EXISTS last_nudge_date DATE;

CREATE TABLE IF NOT EXISTS milestone_awards (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    member_id   BIGINT NOT NULL,
    milestone   INT NOT NULL,
    awarded_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_member_milestone (member_id, milestone),
    CONSTRAINT fk_milestone_member FOREIGN KEY (member_id) REFERENCES members (id)
);

CREATE TABLE IF NOT EXISTS class_feedback (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    member_id   BIGINT NOT NULL,
    class_date  DATE NOT NULL,
    class_type  VARCHAR(50),
    rating      TINYINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_feedback_member FOREIGN KEY (member_id) REFERENCES members (id)
);
