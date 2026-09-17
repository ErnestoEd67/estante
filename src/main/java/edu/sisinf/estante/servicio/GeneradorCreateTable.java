package edu.sisinf.estante.servicio;

import edu.sisinf.estante.dao.IConexionDAO;
import edu.sisinf.estante.modelo.Conexion;
import edu.sisinf.estante.modelo.TipoMotor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Genera una sentencia DDL CREATE TABLE a partir de la estructura de una tabla existente.
 *
 * <p>Utiliza {@link DatabaseMetaData} para inspeccionar columnas, tipos y restricciones
 * de clave primaria, y luego construye una cadena CREATE TABLE sintácticamente correcta
 * para MySQL, PostgreSQL o SQLite.</p>
 *
 * <p>Útil para ingeniería inversa: replicar la estructura de una tabla en otro
 * servidor o guardar el esquema como un script.</p>
 */
public class GeneradorCreateTable {

    /** Constructor privado — clase utilitaria, no instanciable. */
    private GeneradorCreateTable() {}

    /**
     * Genera una sentencia DDL CREATE TABLE para la tabla dada.
     *
     * @param tableName nombre de la tabla a la que se le aplicará ingeniería inversa
     * @param conexion  datos de conexión utilizados para abrir la sesión JDBC
     * @param dao       implementación del DAO que coincide con el motor objetivo
     * @param motor     motor de base de datos objetivo
     * @return cadena DDL que comienza con {@code CREATE TABLE}
     * @throws SQLException si no se pueden recuperar los metadatos
     */
    public static String generar(String tableName, Conexion conexion,
                                 IConexionDAO dao, TipoMotor motor) throws SQLException {

        try (Connection connection = dao.abrir(conexion)) {
            DatabaseMetaData metadata = connection.getMetaData();

            List<String> primaryKeys = getPrimaryKeys(metadata, tableName);
            List<String> columnDefinitions = getColumnDefinitions(metadata, tableName, motor);

            return buildDDL(tableName, columnDefinitions, primaryKeys, motor);
        }
    }

    /**
     * Recupera los nombres de las columnas que forman la clave primaria para la tabla dada.
     */
    private static List<String> getPrimaryKeys(DatabaseMetaData metadata,
                                               String tableName) throws SQLException {
        List<String> primaryKeys = new ArrayList<>();

        try (ResultSet rs = metadata.getPrimaryKeys(null, null, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        return primaryKeys;
    }

    /**
     * Construye las definiciones de columnas a partir de los metadatos de la tabla.
     */
    private static List<String> getColumnDefinitions(DatabaseMetaData metadata,
                                                     String tableName,
                                                     TipoMotor motor) throws SQLException {
        List<String> definitions = new ArrayList<>();

        try (ResultSet rs = metadata.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int columnSize = rs.getInt("COLUMN_SIZE");
                String nullable = rs.getString("IS_NULLABLE");

                String sqlType = buildSqlType(typeName, columnSize, motor);
                String nullConstraint = "NO".equals(nullable) ? " NOT NULL" : "";

                definitions.add("    " + quoteIdentifier(columnName, motor)
                        + " " + sqlType + nullConstraint);
            }
        }

        return definitions;
    }

    /**
     * Da formato a la cadena del tipo SQL de acuerdo con el motor objetivo.
     */
    private static String buildSqlType(String typeName, int columnSize, TipoMotor motor) {
        String upperType = typeName.toUpperCase();

        if (motor == TipoMotor.SQLITE) {
            return buildSqliteType(upperType);
        }

        if (motor == TipoMotor.POSTGRESQL) {
            return buildPostgresqlType(upperType, columnSize);
        }

        if ((upperType.contains("VARCHAR") || upperType.contains("CHAR"))
                && columnSize > 0) {
            return upperType + "(" + columnSize + ")";
        }

        return upperType;
    }

    /**
     * Convierte el tipo de los metadatos a una afinidad válida de SQLite.
     */
    private static String buildSqliteType(String upperType) {
        if (upperType.contains("INT")) {
            return "INTEGER";
        }
        if (upperType.contains("CHAR")
                || upperType.contains("TEXT")
                || upperType.contains("CLOB")) {
            return "TEXT";
        }
        if (upperType.contains("REAL")
                || upperType.contains("FLOA")
                || upperType.contains("DOUB")) {
            return "REAL";
        }
        if (upperType.contains("BLOB")) {
            return "BLOB";
        }
        return "NUMERIC";
    }

    /**
     * Convierte tipos comunes de JDBC y otros motores a tipos válidos de PostgreSQL.
     */
    private static String buildPostgresqlType(String upperType, int columnSize) {
        if (upperType.contains("SMALLSERIAL")) {
            return "SMALLSERIAL";
        }
        if (upperType.contains("BIGSERIAL")) {
            return "BIGSERIAL";
        }
        if (upperType.contains("SERIAL")) {
            return "SERIAL";
        }
        if (upperType.contains("BOOL") || upperType.equals("BIT")) {
            return "BOOLEAN";
        }
        if (upperType.contains("TEXT")
                || upperType.contains("CLOB")
                || upperType.contains("LONGVARCHAR")) {
            return "TEXT";
        }
        if (upperType.contains("VARCHAR") || upperType.contains("CHARACTER VARYING")) {
            return columnSize > 0 ? "VARCHAR(" + columnSize + ")" : "VARCHAR";
        }
        if (upperType.contains("CHAR")) {
            return columnSize > 0 ? "CHAR(" + columnSize + ")" : "CHAR";
        }
        if (upperType.contains("BIGINT")) {
            return "BIGINT";
        }
        if (upperType.contains("SMALLINT") || upperType.contains("TINYINT")) {
            return "SMALLINT";
        }
        if (upperType.contains("INT")) {
            return "INTEGER";
        }
        if (upperType.contains("BLOB")
                || upperType.contains("BINARY")
                || upperType.contains("VARBINARY")) {
            return "BYTEA";
        }
        if (upperType.contains("DOUBLE")) {
            return "DOUBLE PRECISION";
        }
        if (upperType.contains("FLOAT") || upperType.contains("REAL")) {
            return "REAL";
        }
        if (upperType.contains("DATETIME")) {
            return "TIMESTAMP";
        }
        return upperType;
    }

    /**
     * Ensambla la cadena DDL final del CREATE TABLE.
     */
    private static String buildDDL(String tableName,
                                   List<String> columnDefinitions,
                                   List<String> primaryKeys,
                                   TipoMotor motor) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ")
                .append(quoteIdentifier(tableName, motor))
                .append(" (\n");

        ddl.append(String.join(",\n", columnDefinitions));

        if (!primaryKeys.isEmpty()) {
            List<String> quotedPrimaryKeys = primaryKeys.stream()
                    .map(primaryKey -> quoteIdentifier(primaryKey, motor))
                    .toList();
            ddl.append(",\n    PRIMARY KEY (")
                    .append(String.join(", ", quotedPrimaryKeys))
                    .append(")");
        }

        ddl.append("\n)");

        if (motor == TipoMotor.MYSQL) {
            ddl.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        ddl.append(";");
        return ddl.toString();
    }

    /**
     * Aplica las comillas de identificador apropiadas para cada motor.
     */
    private static String quoteIdentifier(String identifier, TipoMotor motor) {
        if (motor == TipoMotor.MYSQL) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
