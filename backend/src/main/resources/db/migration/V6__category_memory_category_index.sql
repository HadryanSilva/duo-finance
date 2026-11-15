-- category_memory cascades on category delete; without this index each delete scans the table.
CREATE INDEX idx_category_memory_category_id ON category_memory (category_id);
