-- Shared base file name for the room's single document (extension stays derived
-- from content-based language detection on the client). NULL on pre-V2 rows == unnamed.
ALTER TABLE document_snapshot ADD COLUMN IF NOT EXISTS file_name VARCHAR(64);
