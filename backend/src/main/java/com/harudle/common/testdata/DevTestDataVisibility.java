package com.harudle.common.testdata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 계정 확인은 기존 쿼리 안에서 처리한다. 이 설정 객체는 DB를 조회하지 않는다. */
@Component
public class DevTestDataVisibility {
    public static final String TEST_VIEWER_HQL = """
            EXISTS (SELECT testViewer.id FROM User testViewer
                WHERE testViewer.id = :viewerId AND testViewer.deletedAt IS NULL
                  AND testViewer.primaryEmail LIKE '%.loadtest.invalid')
            """;
    public static final String VISIBLE_AUTHOR_HQL = """
             AND (author.primaryEmail IS NULL OR author.primaryEmail NOT LIKE '%.loadtest.invalid'
                  OR
            """ + TEST_VIEWER_HQL + ")";
    public static final String TEST_VIEWER_SQL = """
            EXISTS (SELECT 1 FROM users test_viewer
                WHERE test_viewer.id = CAST(:viewerId AS uuid) AND test_viewer.deleted_at IS NULL
                  AND test_viewer.primary_email LIKE '%.loadtest.invalid')
            """;

    private final boolean enabled;

    public DevTestDataVisibility(@Value("${DEPLOY_ENV:}") String environment) {
        this.enabled = "dev".equals(environment);
    }

    public boolean enabled() {
        return enabled;
    }
}
