package com.harudle.generation.adapter.out.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresImageBackupExecutionLockTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement acquire = mock(PreparedStatement.class);
    private final PreparedStatement release = mock(PreparedStatement.class);
    private final ResultSet acquired = mock(ResultSet.class);
    private final ResultSet released = mock(ResultSet.class);
    private final Runnable operation = mock(Runnable.class);
    private final PostgresImageBackupExecutionLock lock = new PostgresImageBackupExecutionLock(source);

    @BeforeEach
    void setUp() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(release.executeQuery()).thenReturn(released);
        when(acquired.next()).thenReturn(true);
        when(acquired.getBoolean(1)).thenReturn(true);
        when(released.next()).thenReturn(true);
        when(released.getBoolean(1)).thenReturn(true);
    }

    @Test
    void holdsSameConnectionUntilOperationCompletesAndReleasesBeforeReturningToPool() throws Exception {
        assertThat(lock.executeIfAvailable(operation)).isTrue();
        var order = inOrder(connection, acquire, operation, release);
        order.verify(connection).prepareStatement("SELECT pg_try_advisory_lock(?)");
        order.verify(acquire).executeQuery();
        order.verify(operation).run();
        order.verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
        order.verify(release).executeQuery();
        order.verify(connection).close();
        verify(source).getConnection();
    }

    @Test
    void skipsWorkWithoutUnlockingAnotherExecution() throws Exception {
        when(acquired.getBoolean(1)).thenReturn(false);
        assertThat(lock.executeIfAvailable(operation)).isFalse();
        verifyNoInteractions(operation, release);
        verify(connection).close();
    }

    @Test
    void operationFailureStillReleasesLockAndPreservesCause() throws Exception {
        var failure = new IllegalArgumentException("failed");
        doThrow(failure).when(operation).run();
        assertThatThrownBy(() -> lock.executeIfAvailable(operation)).isSameAs(failure);
        verify(release).executeQuery();
        verify(connection).close();
    }

    @Test
    void uncertainAcquisitionDiscardsConnectionWithoutStartingWork() throws Exception {
        when(acquire.executeQuery()).thenThrow(new SQLException("response lost"));
        assertThatThrownBy(() -> lock.executeIfAvailable(operation)).isInstanceOf(IllegalStateException.class);
        verify(connection).abort(any());
        verifyNoInteractions(operation);
        verify(connection).close();
    }

    @Test
    void failedUnlockDiscardsSessionInsteadOfReturningHeldLockToPool() throws Exception {
        when(released.getBoolean(1)).thenReturn(false);
        assertThatThrownBy(() -> lock.executeIfAvailable(operation)).isInstanceOf(IllegalStateException.class);
        verify(connection).abort(any());
        verify(connection).close();
    }

    @Test
    void unlockFailureDoesNotHideOperationFailure() throws Exception {
        var failure = new IllegalArgumentException("failed");
        var unlockFailure = new SQLException("unlock failed");
        doThrow(failure).when(operation).run();
        when(release.executeQuery()).thenThrow(unlockFailure);
        assertThatThrownBy(() -> lock.executeIfAvailable(operation)).isSameAs(failure)
                .satisfies(error -> assertThat(error.getSuppressed()).contains(unlockFailure));
        verify(connection).abort(any());
    }
}
