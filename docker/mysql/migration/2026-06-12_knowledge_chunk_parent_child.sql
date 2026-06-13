-- Parent-child chunking (small-to-big) schema migration: add columns to knowledge_chunk.
-- Run ONCE on an EXISTING database (new DBs already get these via init.sql).
-- parent_id : child points to its parent; parent / legacy-flat chunks are NULL.
-- chunk_role: 'parent' / 'child'; NULL = legacy flat chunk (backward compatible).
-- ASCII-only on purpose: avoids client/pipe charset mangling the SQL.

USE aichatpilot;

ALTER TABLE knowledge_chunk
    ADD COLUMN parent_id  BIGINT      NULL COMMENT 'parent chunk id; NULL for parent or legacy chunk' AFTER vector_id,
    ADD COLUMN chunk_role VARCHAR(16) NULL COMMENT 'parent / child; NULL = legacy flat chunk' AFTER parent_id,
    ADD INDEX idx_parent (parent_id);
