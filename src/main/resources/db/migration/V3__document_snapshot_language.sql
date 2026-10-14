-- Language chosen for the room's document from the client's language picker.
-- NULL on pre-V3 rows == auto-detect.
ALTER TABLE document_snapshot ADD COLUMN IF NOT EXISTS language VARCHAR(32);
