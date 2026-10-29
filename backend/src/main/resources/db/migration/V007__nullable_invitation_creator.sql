-- V007: Allow NULL created_by_user_id in invitations table
-- Supports system-generated bootstrap invitation when the database contains zero users.

ALTER TABLE invitations MODIFY COLUMN created_by_user_id CHAR(26) NULL;

