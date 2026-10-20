package io.github.eytoptas.teamchest.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DataSourceSettingsTests extends PersistenceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void parsedStatementsAreCachedPerConnection() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            JdbcConnection mysql = connection.unwrap(JdbcConnection.class);

            assertThat(mysql.getPropertySet().getBooleanProperty(PropertyKey.cachePrepStmts).getValue()).isTrue();
            assertThat(mysql.getPropertySet().getIntegerProperty(PropertyKey.prepStmtCacheSize).getValue()).isEqualTo(250);
            assertThat(mysql.getPropertySet().getIntegerProperty(PropertyKey.prepStmtCacheSqlLimit).getValue()).isEqualTo(2048);
            assertThat(mysql.getPropertySet().getBooleanProperty(PropertyKey.useServerPrepStmts).getValue()).isFalse();
        }
    }
}
