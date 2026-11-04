ALTER TABLE comments ALTER COLUMN author_id DROP NOT NULL;
ALTER TABLE comments DROP CONSTRAINT fk_comments_author;
ALTER TABLE comments ADD CONSTRAINT fk_comments_author
    FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE SET NULL;

CREATE TABLE withdrawn_comment_hidden_viewers (
    comment_id UUID NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (comment_id, viewer_id)
);

CREATE INDEX idx_withdrawn_comment_hidden_viewers_viewer
    ON withdrawn_comment_hidden_viewers (viewer_id);
