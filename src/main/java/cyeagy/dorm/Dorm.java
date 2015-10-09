package cyeagy.dorm;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static cyeagy.dorm.ReflectUtil.*;

/**
 * Joinless ORM. No setup required.
 */
public class Dorm {
    private static final SqlGenerator GENERATOR = SqlGenerator.fromDefaults();

    public static Dorm fromDefaults() {
        return new Dorm();
    }

    private Dorm() { }

    /**
     * execute a select query filtering on the primary key.
     *
     * @param connection db connection. close it yourself
     * @param key        primary key to filter on
     * @param clazz      entity type class
     * @param <T>        entity type
     * @return matching entity or null
     * @throws Exception
     */
    public <T> T select(Connection connection, Object key, Class<T> clazz) throws Exception {
        T result = null;
        final TableData tableData = TableData.from(clazz);
        final String select = GENERATOR.generateSelectSqlTemplate(tableData);
        try (final BetterPreparedStatement ps = BetterPreparedStatement.create(connection, select)) {
            setParameter(ps, key, 1);
            try (final BetterResultSet rs = BetterResultSet.from(ps.executeQuery())) {
                if (rs.next()) {
                    result = constructNewInstance(clazz);
                    setField(rs, tableData.getPrimaryKey(), result, null);
                    for (Field field : tableData.getColumns()) {
                        setField(rs, field, result, null);
                    }
                }
            }
        }
        return result;
    }

    /**
     * execute a select filtering on a set of primary keys.
     * only known to work on postgres.
     *
     * @param connection db connection. close it yourself
     * @param keys       primary keys to filter on
     * @param clazz      entity type class
     * @param <T>        entity type
     * @return matching entities or empty set
     * @throws Exception
     */
    public <T> Set<T> select(Connection connection, Collection<?> keys, Class<T> clazz) throws Exception {
        final TableData tableData = TableData.from(clazz);
        final String select = GENERATOR.generateBulkSelectSqlTemplate(tableData);
        final Set<?> keySet = keys instanceof Set ? (Set<?>) keys : new HashSet<>(keys);
        final Set<T> results = new HashSet<>(keySet.size());
        try (final BetterPreparedStatement ps = BetterPreparedStatement.create(connection, select)) {
            ps.setArray(1, keySet);
            try (final BetterResultSet rs = BetterResultSet.from(ps.executeQuery())) {
                while (rs.next()) {
                    final T result = constructNewInstance(clazz);
                    setField(rs, tableData.getPrimaryKey(), result, null);
                    for (Field field : tableData.getColumns()) {
                        setField(rs, field, result, null);
                    }
                    results.add(result);
                }
            }
        }
        return results;
    }

    /**
     * execute insert.
     * for auto-generated keys, the primary key has to be null in the entity.
     * if the primary key is non-null, the insert will be formatted to insert with that specified primary key.
     * <p>
     * dorm will extract the generated key and return it in a copied entity object.
     * this can return a null object it fails to get the generated key for some reason.
     * if the primary key is non-null, the insert will simply return itself.
     *
     * @param connection db connection. close it yourself
     * @param entity     entity to insert
     * @param <T>        entity type
     * @return entity with generated primary key. null if there was no generated key returned. the entity itself if the primary key was non-null.
     * @throws Exception
     */
    public <T> T insert(Connection connection, T entity) throws Exception {
        T result = null;
        final TableData tableData = TableData.from(entity.getClass());
        final Object pk = tableData.getPrimaryKey().getType().isPrimitive() ? null : readField(tableData.getPrimaryKey(), entity);
        final String insert = GENERATOR.generateInsertSqlTemplate(tableData, pk != null);
        try (final BetterPreparedStatement ps = BetterPreparedStatement.create(connection, insert, true)) {
            int idx = 0;
            if (pk != null) {
                setParameter(ps, tableData.getPrimaryKey(), entity, ++idx);
            }
            for (Field field : tableData.getColumns()) {
                setParameter(ps, field, entity, ++idx);
            }
            ps.execute();
            if (pk == null) {
                try (final BetterResultSet rs = BetterResultSet.from(ps.getGeneratedKeys())) {
                    if (rs.next()) {
                        //noinspection unchecked
                        result = constructNewInstance((Class<T>) entity.getClass());
                        setField(rs, tableData.getPrimaryKey(), result, 1);
                        for (Field field : tableData.getColumns()) {
                            copyField(field, result, entity);
                        }
                    }
                }
            } else {
                result = entity;
            }
        }
        return result;
    }

    /**
     * execute update.
     *
     * @param connection db connection. close it yourself
     * @param entity     entity to update
     * @throws Exception
     */
    public void update(Connection connection, Object entity) throws Exception {
        final TableData tableData = TableData.from(entity.getClass());
        final String update = GENERATOR.generateUpdateSqlTemplate(tableData);
        try (final BetterPreparedStatement ps = BetterPreparedStatement.create(connection, update)) {
            int idx = 0;
            for (Field field : tableData.getColumns()) {
                setParameter(ps, field, entity, ++idx);
            }
            setParameter(ps, tableData.getPrimaryKey(), entity, ++idx);
            ps.execute();
        }
    }

    /**
     * execute delete.
     *
     * @param connection db connection. close it yourself
     * @param key        primary key to filter on
     * @param clazz      entity type class
     * @throws Exception
     */
    public void delete(Connection connection, Object key, Class<?> clazz) throws Exception {
        final TableData tableData = TableData.from(clazz);
        final String delete = GENERATOR.generateDeleteSqlTemplate(tableData);
        try (final BetterPreparedStatement ps = BetterPreparedStatement.create(connection, delete)) {
            setParameter(ps, key, 1);
            ps.execute();
        }
    }

    private void copyField(Field field, Object target, Object origin) throws IllegalAccessException {
        final TypeMappers.FieldCopier copier = TypeMappers.getFieldCopier(field.getType());
        if (copier != null) {
            copier.copy(field, target, origin);
        } else {
            writeField(field, target, readField(field, origin));
        }
    }

    private void setField(BetterResultSet rs, Field field, Object target, Integer idx) throws SQLException, IllegalAccessException {
        final TypeMappers.FieldResultWriter writer = TypeMappers.getFieldResultWriter(field.getType());
        if (writer != null) {
            writer.write(rs, field, target, idx);
        } else {
            final Object v = idx == null ? rs.getObject(TableData.getColumnName(field)) : rs.getObject(idx);
            writeField(field, target, v);
        }
    }

    private void setParameter(BetterPreparedStatement ps, Object value, int idx) throws SQLException {
        final TypeMappers.ObjectParamSetter setter = TypeMappers.getObjectParamSetter(value.getClass());
        if (setter != null) {
            setter.set(ps, value, idx);
        } else {
            ps.setObject(idx, value);
        }
    }

    private void setParameter(BetterPreparedStatement ps, Field field, Object target, int idx) throws IllegalAccessException, SQLException {
        final TypeMappers.FieldParamSetter setter = TypeMappers.getFieldParamSetter(field.getType());
        if (setter != null) {
            setter.set(ps, field, target, idx);
        } else {
            ps.setObject(idx, readField(field, target));
        }
    }
}
