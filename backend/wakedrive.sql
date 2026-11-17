CREATE DATABASE IF NOT EXISTS `wakedrive`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `wakedrive`;

CREATE TABLE IF NOT EXISTS `users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(24) NOT NULL,
  `password_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `password_salt` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `role` VARCHAR(16) NOT NULL DEFAULT 'user',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing installations are migrated additively by the application.
-- This file provisions a new database; it contains no account passwords.
CREATE TABLE IF NOT EXISTS `admin_sessions` (
  `token_hash` VARCHAR(64) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `expires_at` VARCHAR(40) NOT NULL,
  `created_at` VARCHAR(40) NOT NULL,
  PRIMARY KEY (`token_hash`),
  KEY `idx_admin_sessions_expiry` (`expires_at`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `fatigue_events` (
  `id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `created_at` VARCHAR(40) NOT NULL,
  `severity` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `payload_json` TEXT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_fatigue_user_created` (`user_id`, `created_at`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `monitor_status` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `updated_at` VARCHAR(40) NOT NULL,
  `payload_json` TEXT NOT NULL,
  PRIMARY KEY (`user_id`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user_settings` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `payload_json` TEXT NOT NULL,
  PRIMARY KEY (`user_id`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `monitoring_reports` (
  `id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `client_id` VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  `started_at` VARCHAR(40) NOT NULL,
  `source` VARCHAR(16) NOT NULL,
  `payload_json` MEDIUMTEXT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_reports_user_client` (`user_id`, `client_id`),
  KEY `idx_reports_user_started` (`user_id`, `started_at`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `sessions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expires_at` DATETIME(6) NOT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sessions_token_hash` (`token_hash`),
  KEY `idx_sessions_expires_at` (`expires_at`),
  CONSTRAINT `fk_sessions_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
