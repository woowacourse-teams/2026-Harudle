package com.harudle.generation.diary.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DiaryGenerationAdmissionLock {

    private static final long LOCK_KEY = 0x48415255444c4533L;

    private final JdbcTemplate jdbcTemplate;

    public DiaryGenerationAdmissionLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
    }
}
