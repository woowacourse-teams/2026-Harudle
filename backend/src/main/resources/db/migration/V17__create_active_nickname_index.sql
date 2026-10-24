-- Run outside a transaction; V14 must have committed before scanning users.
-- The profile service also checks collisions with legacy name fallbacks.
CREATE UNIQUE INDEX CONCURRENTLY uq_users_active_nickname
    ON users (nickname)
    WHERE nickname IS NOT NULL AND deleted_at IS NULL;
