package com.nanth.querion.services;

import com.nanth.querion.exceptions.SchemaReadException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class SchemaService {
  private static final Set<String> EXCLUDED_SCHEMAS =
      Set.of("information_schema", "pg_catalog");

  private final DataSource dataSource;

  public SchemaService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public String getSchema() {
    return formatSchema(readSchemaSnapshot());
  }

  public DatabaseSchemaSnapshot readSchemaSnapshot() {
    try (Connection conn = dataSource.getConnection()) {
      DatabaseMetaData metaData = conn.getMetaData();
      List<TableSchema> tables = readTables(metaData);
      return new DatabaseSchemaSnapshot(tables);
    } catch (Exception ex) {
      throw new SchemaReadException("Failed to read database schema.", ex);
    }
  }

  private String formatSchema(DatabaseSchemaSnapshot snapshot) {
    StringBuilder schema = new StringBuilder();

    for (TableSchema table : snapshot.tables()) {
      schema.append("Table: ").append(table.name()).append("\n");

      if (!table.primaryKeys().isEmpty()) {
        schema.append("Primary key: ")
            .append(String.join(", ", table.primaryKeys()))
            .append("\n");
      }

      schema.append("Columns:\n");
      for (ColumnSchema column : table.columns()) {
        schema.append("- ")
            .append(column.name())
            .append(" ")
            .append(column.dataType());

        if (column.nullable()) {
          schema.append(" nullable");
        } else {
          schema.append(" not null");
        }

        if (column.primaryKey()) {
          schema.append(" primary key");
        }

        if (column.foreignKeyReference() != null) {
          schema.append(" references ").append(column.foreignKeyReference());
        }

        schema.append("\n");
      }

      if (!table.relationships().isEmpty()) {
        schema.append("Relationships:\n");
        for (String relationship : table.relationships()) {
          schema.append("- ").append(relationship).append("\n");
        }
      }

      schema.append("\n");
    }

    return schema.toString().trim();
  }

  private List<TableSchema> readTables(DatabaseMetaData metaData) throws Exception {
    List<TableSchema> tables = new ArrayList<>();

    try (ResultSet tableResults = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
      while (tableResults.next()) {
        String schemaName = tableResults.getString("TABLE_SCHEM");
        if (schemaName != null && EXCLUDED_SCHEMAS.contains(schemaName.toLowerCase())) {
          continue;
        }

        String tableName = tableResults.getString("TABLE_NAME");
        List<String> primaryKeys = readPrimaryKeys(metaData, schemaName, tableName);
        Map<String, String> foreignKeys = readForeignKeys(metaData, schemaName, tableName);
        List<ColumnSchema> columns = readColumns(metaData, schemaName, tableName, primaryKeys, foreignKeys);
        List<String> relationships = buildRelationships(foreignKeys);

        tables.add(new TableSchema(schemaName, tableName, columns, primaryKeys, relationships));
      }
    }

    tables.sort(Comparator.comparing(TableSchema::name));
    return tables;
  }

  private List<String> readPrimaryKeys(DatabaseMetaData metaData, String schemaName, String tableName)
      throws Exception {
    List<String> primaryKeys = new ArrayList<>();
    try (ResultSet resultSet = metaData.getPrimaryKeys(null, schemaName, tableName)) {
      while (resultSet.next()) {
        primaryKeys.add(resultSet.getString("COLUMN_NAME"));
      }
    }
    primaryKeys.sort(String::compareTo);
    return primaryKeys;
  }

  private Map<String, String> readForeignKeys(DatabaseMetaData metaData, String schemaName, String tableName)
      throws Exception {
    Map<String, String> foreignKeys = new LinkedHashMap<>();
    try (ResultSet resultSet = metaData.getImportedKeys(null, schemaName, tableName)) {
      while (resultSet.next()) {
        String fkColumn = resultSet.getString("FKCOLUMN_NAME");
        String pkTable = resultSet.getString("PKTABLE_NAME");
        String pkColumn = resultSet.getString("PKCOLUMN_NAME");
        foreignKeys.put(fkColumn, pkTable + "." + pkColumn);
      }
    }
    return foreignKeys;
  }

  private List<ColumnSchema> readColumns(DatabaseMetaData metaData, String schemaName, String tableName,
      List<String> primaryKeys, Map<String, String> foreignKeys) throws Exception {
    List<ColumnSchema> columns = new ArrayList<>();

    try (ResultSet resultSet = metaData.getColumns(null, schemaName, tableName, "%")) {
      while (resultSet.next()) {
        String columnName = resultSet.getString("COLUMN_NAME");
        String dataType = resultSet.getString("TYPE_NAME");
        boolean nullable = DatabaseMetaData.columnNullable == resultSet.getInt("NULLABLE");

        columns.add(new ColumnSchema(
            columnName,
            dataType,
            nullable,
            primaryKeys.contains(columnName),
            foreignKeys.get(columnName)
        ));
      }
    }

    return columns;
  }

  private List<String> buildRelationships(Map<String, String> foreignKeys) {
    Set<String> relationships = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : foreignKeys.entrySet()) {
      relationships.add(entry.getKey() + " -> " + entry.getValue());
    }
    return new ArrayList<>(relationships);
  }

  public record DatabaseSchemaSnapshot(
      List<TableSchema> tables
  ) {
  }

  public record TableSchema(
      String schemaName,
      String name,
      List<ColumnSchema> columns,
      List<String> primaryKeys,
      List<String> relationships
  ) {
  }

  public record ColumnSchema(
      String name,
      String dataType,
      boolean nullable,
      boolean primaryKey,
      String foreignKeyReference
  ) {
  }
}
