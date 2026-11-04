ALTER TABLE feed_likes ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE feed_likes DROP CONSTRAINT fk_feed_likes_user;
ALTER TABLE feed_likes ADD CONSTRAINT fk_feed_likes_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL;
