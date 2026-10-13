package com.harudle.category.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.category.service.exception.CategoryNameAlreadyUsedException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CategoryCreateService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CategoryCreateServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private CategoryCreateService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("동시에 같은 이름을 생성하면 한 요청만 성공하고 한 행만 저장된다")
    void rejectsConcurrentDuplicateName() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterBarrier(barrier));
            var second = executor.submit(() -> createAfterBarrier(barrier));

            assertThat(new boolean[]{first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE name = '동시 생성'", Integer.class
            )).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM categories WHERE name = '동시 생성'");
        }
    }

    private boolean createAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            service.create("동시 생성", 0);
            return true;
        } catch (CategoryNameAlreadyUsedException exception) {
            return false;
        }
    }
}
