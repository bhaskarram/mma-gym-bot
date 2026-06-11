-- MMA Gym Bot — MySQL Schema
-- Run once on a fresh database: mysql -u root -p mmagym < schema.sql

CREATE DATABASE IF NOT EXISTS mmagym CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mmagym;

CREATE TABLE IF NOT EXISTS members (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(100) NOT NULL,
    phone             VARCHAR(20)  NOT NULL,
    join_date         DATE,
    plan_type         ENUM('MONTHLY','QUARTERLY','ANNUAL') NOT NULL,
    plan_expiry       DATE,
    payment_status    ENUM('PAID','UNPAID','EXPIRED')      NOT NULL DEFAULT 'UNPAID',
    status            ENUM('ACTIVE','INACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
    belt_rank         ENUM('WHITE','BLUE','PURPLE','BROWN','BLACK') NOT NULL DEFAULT 'WHITE',
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
    max_capacity  INT          NOT NULL DEFAULT 20,
    coach         VARCHAR(100),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Seed sample classes (edit as needed)
INSERT IGNORE INTO gym_classes (class_name, day_of_week, class_time, coach) VALUES
    ('Monday Striking',     1, '07:00:00', 'Coach Raj'),
    ('Monday Grappling',    1, '19:00:00', 'Coach Raj'),
    ('Wednesday MMA',       3, '07:00:00', 'Coach Raj'),
    ('Wednesday Grappling', 3, '19:00:00', 'Coach Raj'),
    ('Friday Striking',     5, '07:00:00', 'Coach Raj'),
    ('Friday MMA',          5, '19:00:00', 'Coach Raj'),
    ('Saturday Conditioning',6,'09:00:00', 'Coach Raj');
