package io.github.eytoptas.teamchest.persistence;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public final class RecordedSql implements StatementInspector {

    private static final ThreadLocal<List<String>> RECORDING = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> statements = RECORDING.get();
        if (statements != null) {
            statements.add(sql);
        }
        return sql;
    }

    public static List<String> during(Runnable work) {
        List<String> statements = new ArrayList<>();
        RECORDING.set(statements);
        try {
            work.run();
        } finally {
            RECORDING.remove();
        }
        return List.copyOf(statements);
    }
}
