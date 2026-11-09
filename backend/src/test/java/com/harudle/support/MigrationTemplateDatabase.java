package com.harudle.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 마이그레이션 테스트가 매번 같은 상태로 다시 만드는 DB를 PostgreSQL 템플릿 DB로 재사용한다.
 *
 * <p>상태 이름별로 처음 한 번만 준비 코드를 실제로 실행하고 그 결과를 템플릿 DB로 저장한다.
 * 이후에는 컨테이너의 기본 DB를 지우고 템플릿을 복사해 같은 상태에서 시작한다.
 * 준비 코드는 기존처럼 컨테이너의 기본 DB에 대해 실행하므로, Flyway 이력을 포함한 DB 전체가 그대로 복사된다.
 * 역할(role)처럼 클러스터 전체에 속한 객체는 복사 대상이 아니므로 테스트가 직접 정리한다.</p>
 *
 * <p>클래스별 컨테이너를 사용하는 마이그레이션 테스트 전용이다. 일반 통합 테스트의 공유 DB에는 사용하지 않는다.</p>
 */
public final class MigrationTemplateDatabase {

    private final PostgreSQLContainer container;
    private final Set<String> templates = new HashSet<>();

    public MigrationTemplateDatabase(PostgreSQLContainer container) {
        this.container = container;
    }

    /**
     * 컨테이너의 기본 DB를 {@code state} 상태로 다시 만든다.
     * 해당 상태의 템플릿이 없으면 빈 DB에서 {@code preparer}를 실행해 만든 뒤 템플릿으로 저장한다.
     */
    public void restore(String state, Preparer preparer) throws SQLException {
        String database = container.getDatabaseName();
        String template = templateName(state);
        if (!templates.contains(template)) {
            recreate(database, null);
            preparer.prepare();
            try (Connection admin = admin()) {
                terminateSessions(admin, database);
                execute(admin, "DROP DATABASE IF EXISTS " + template + " WITH (FORCE)");
                execute(admin, "CREATE DATABASE " + template + " TEMPLATE " + database);
            }
            templates.add(template);
            return;
        }
        recreate(database, template);
    }

    private void recreate(String database, String template) throws SQLException {
        try (Connection admin = admin()) {
            execute(admin, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            execute(admin, "CREATE DATABASE " + database + (template == null ? "" : " TEMPLATE " + template));
        }
    }

    private Connection admin() throws SQLException {
        String url = container.getJdbcUrl()
                .replaceFirst("/" + container.getDatabaseName() + "(?=\\?|$)", "/postgres");
        return DriverManager.getConnection(url, container.getUsername(), container.getPassword());
    }

    private static void terminateSessions(Connection admin, String database) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement("""
                SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                WHERE datname = ? AND pid <> pg_backend_pid()
                """)) {
            statement.setString(1, database);
            statement.execute();
        }
    }

    private static String templateName(String state) {
        return "migration_template_" + state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @FunctionalInterface
    public interface Preparer {
        void prepare() throws SQLException;
    }
}
