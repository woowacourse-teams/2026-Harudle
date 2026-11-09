package com.harudle.diary.infrastructure.migration;

import java.sql.Connection;

/** Proves the completed v1-only schema without scanning diary or generation rows. */
public final class DiarySourceTextReadiness {

    private final DiarySourceTextMigrationSession jdbc;
    private final DiarySourceTextMigrationSchema schema;

    public DiarySourceTextReadiness(Connection connection) {
        this(new DiarySourceTextMigrationSession(connection));
    }

    DiarySourceTextReadiness(DiarySourceTextMigrationSession jdbc) {
        if (jdbc == null) {
            throw new DiarySourceTextMigrationException();
        }
        this.jdbc = jdbc;
        this.schema = new DiarySourceTextMigrationSchema(jdbc);
    }

    /** Keeps schema checks and the completed constraint proof in the same locked transaction. */
    public void requireReady() {
        jdbc.safely(() -> jdbc.transaction(() -> {
            schema.lockAndRequire();
            if (!schema.isFinalized()) {
                throw new DiarySourceTextMigrationException();
            }
            return null;
        }));
    }
}
