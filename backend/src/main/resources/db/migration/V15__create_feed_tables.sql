-- Categories are archived via is_active; existing feed references are retained.
CREATE TABLE categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    name VARCHAR(20) COLLATE "C" NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT ck_categories_name
        CHECK (
            char_length(name) BETWEEN 1 AND 20
            AND name !~ '^[[:space:]]|[[:space:]]$'
            AND name IS NFC NORMALIZED
        ),
    CONSTRAINT ck_categories_sort_order CHECK (sort_order >= 0)
);

INSERT INTO categories (name, sort_order)
VALUES ('일상', 0), ('우테코', 1);

CREATE INDEX idx_categories_active_order
    ON categories (sort_order, id)
    WHERE is_active = TRUE;

CREATE TABLE feeds (
    id UUID NOT NULL,
    diary_id UUID NOT NULL,
    category_id BIGINT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    like_count INTEGER NOT NULL DEFAULT 0,
    comment_count INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT pk_feeds PRIMARY KEY (id),
    CONSTRAINT ck_feeds_like_count CHECK (like_count >= 0),
    CONSTRAINT ck_feeds_comment_count CHECK (comment_count >= 0),
    CONSTRAINT fk_feeds_diary
        FOREIGN KEY (diary_id) REFERENCES diaries (id) ON DELETE CASCADE,
    CONSTRAINT fk_feeds_category
        FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT
);

-- A deleted feed releases its diary for publication with a new feed ID/event.
CREATE UNIQUE INDEX uq_feeds_active_diary
    ON feeds (diary_id)
    WHERE deleted_at IS NULL;

-- Full FK indexes also cover archived rows during physical cleanup.
CREATE INDEX idx_feeds_diary_id ON feeds (diary_id);
CREATE INDEX idx_feeds_category_id ON feeds (category_id);

CREATE INDEX idx_feeds_latest
    ON feeds (published_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_feeds_category_latest
    ON feeds (category_id, published_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_feeds_popular
    ON feeds (like_count DESC, published_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_feeds_category_popular
    ON feeds (category_id, like_count DESC, published_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE comments (
    id UUID NOT NULL,
    feed_id UUID NOT NULL,
    author_id UUID NOT NULL,
    content VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT pk_comments PRIMARY KEY (id),
    CONSTRAINT ck_comments_content
        CHECK (
            char_length(content) BETWEEN 1 AND 100
            AND content !~ '^[[:space:]]|[[:space:]]$'
        ),
    CONSTRAINT fk_comments_feed
        FOREIGN KEY (feed_id) REFERENCES feeds (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_author
        FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_comments_feed_id ON comments (feed_id);
CREATE INDEX idx_comments_author_id ON comments (author_id);
CREATE INDEX idx_comments_feed_timeline
    ON comments (feed_id, created_at, id)
    WHERE deleted_at IS NULL;

-- Likes are removed physically on cancellation. Each re-like gets a new ID,
-- which becomes the source action ID of a new in-app notification.
CREATE TABLE feed_likes (
    id UUID NOT NULL,
    feed_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_feed_likes PRIMARY KEY (id),
    CONSTRAINT uq_feed_likes_feed_user UNIQUE (feed_id, user_id),
    CONSTRAINT fk_feed_likes_feed
        FOREIGN KEY (feed_id) REFERENCES feeds (id) ON DELETE CASCADE,
    CONSTRAINT fk_feed_likes_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_feed_likes_user_id ON feed_likes (user_id);

-- Service transactions must check diary ownership, successful generation and
-- active category before publishing. They also maintain counts, cascade logical
-- diary/feed deletion and suppress deleted-feed comments/likes in public reads.
-- SQL ON DELETE actions above apply only to physical deletion.
