package com.example.lms.api;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetaDisplayDbQueryConnectionTest {
    @Test
    void setReadOnlyFailureClosesAcquiredConnection() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        doThrow(new SQLException("synthetic read-only failure")).when(connection).setReadOnly(true);
        var response = new MetaDisplayDbQueryController(source).tables();
        assertEquals(500, response.getStatusCode().value());
        assertEquals("tables_failed", response.getBody().get("error"));
        verify(connection).close();
    }

    @Test
    void closeFailureIsSuppressedOnOriginalConfigurationFailure() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        SQLException original = new SQLException("synthetic read-only failure");
        SQLException closing = new SQLException("synthetic close failure");
        doThrow(original).when(connection).setReadOnly(true);
        doThrow(closing).when(connection).close();
        var method = MetaDisplayDbQueryController.class.getDeclaredMethod("readOnlyConnection");
        method.setAccessible(true);
        var failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(new MetaDisplayDbQueryController(source)));
        assertSame(original, failure.getCause());
        assertArrayEquals(new Throwable[]{closing}, original.getSuppressed());
        verify(connection).close();
    }
}
