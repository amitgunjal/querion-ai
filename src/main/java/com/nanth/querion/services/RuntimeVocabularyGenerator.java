package com.nanth.querion.services;

import com.nanth.querion.exceptions.ResourceInitializationException;
import com.nanth.querion.util.RuntimeResourceLoader;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component("runtimeVocabularyGenerator")
public class RuntimeVocabularyGenerator {

  private static final Logger log = LoggerFactory.getLogger(RuntimeVocabularyGenerator.class);
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
  private static final Set<String> FILTER_COLUMN_HINTS =
      Set.of("status", "state", "type", "category", "stage", "mode", "priority");
  private static final int MAX_FILTER_VALUES_PER_COLUMN = 12;
  private static final int MAX_FILTER_COLUMNS = 40;

  private final SchemaService schemaService;
  private final DataSource dataSource;

  public RuntimeVocabularyGenerator(SchemaService schemaService, DataSource dataSource) {
    this.schemaService = schemaService;
    this.dataSource = dataSource;
  }

  @PostConstruct
  public void generate() {
    SchemaService.DatabaseSchemaSnapshot snapshot = schemaService.readSchemaSnapshot();
    writeText("schema/schema.txt", schemaService.getSchema());
    writeLines("classifier/chat-seed-samples.txt", loadBundledLines("classifier/chat-seed-samples.txt"));
    writeLines("classifier/data-nouns.txt", buildDataNouns(snapshot.tables()));
    writeLines("classifier/data-seed-samples.txt", buildDataSeedSamples(snapshot.tables()));
    writeLines("query-reuse/filter-value-tokens.txt", buildFilterValueTokens(snapshot.tables()));
    log.info("Generated runtime vocabulary in {}", RuntimeResourceLoader.resolve("").toAbsolutePath());
  }

  private List<String> buildDataNouns(List<SchemaService.TableSchema> tables) {
    LinkedHashSet<String> nouns = new LinkedHashSet<>();
    for (SchemaService.TableSchema table : tables) {
      String original = normalizePhrase(table.name().replace('_', ' '));
      if (original.isBlank()) {
        continue;
      }
      nouns.add(original);
      nouns.add(singularizePhrase(original));
    }
    return new ArrayList<>(nouns);
  }

  private List<String> buildDataSeedSamples(List<SchemaService.TableSchema> tables) {
    LinkedHashSet<String> samples = new LinkedHashSet<>();
    for (String noun : buildDataNouns(tables)) {
      samples.add("list " + noun);
      samples.add("show " + noun);
      samples.add("get " + noun);
      samples.add("find " + noun);
      samples.add("count " + noun);
      samples.add("hi show " + noun);
      samples.add("hello list " + noun);
    }
    return new ArrayList<>(samples);
  }

  private List<String> buildFilterValueTokens(List<SchemaService.TableSchema> tables) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    values.addAll(List.of("true", "false", "active", "inactive"));

    int scannedColumns = 0;
    for (SchemaService.TableSchema table : tables) {
      for (SchemaService.ColumnSchema column : table.columns()) {
        if (scannedColumns >= MAX_FILTER_COLUMNS) {
          return new ArrayList<>(values);
        }
        if (!isMeaningfulFilterColumn(column)) {
          continue;
        }

        scannedColumns++;
        values.addAll(readDistinctColumnValues(table, column));
      }
    }

    return new ArrayList<>(values);
  }

  private boolean isMeaningfulFilterColumn(SchemaService.ColumnSchema column) {
    String columnName = column.name().toLowerCase(Locale.ROOT);
    String dataType = column.dataType().toLowerCase(Locale.ROOT);

    if (dataType.contains("bool")) {
      return true;
    }

    boolean nameMatch = FILTER_COLUMN_HINTS.stream().anyMatch(columnName::contains);
    boolean textLike = dataType.contains("char") || dataType.contains("text");
    return nameMatch && textLike;
  }

  private List<String> readDistinctColumnValues(
      SchemaService.TableSchema table, SchemaService.ColumnSchema column) {
    if (column.dataType().toLowerCase(Locale.ROOT).contains("bool")) {
      return List.of("true", "false");
    }

    String sql = "SELECT DISTINCT CAST(" + quoteIdentifier(column.name()) + " AS VARCHAR(255)) AS value "
        + "FROM " + qualifiedTableName(table) + " "
        + "WHERE " + quoteIdentifier(column.name()) + " IS NOT NULL "
        + "LIMIT ?";

    LinkedHashSet<String> values = new LinkedHashSet<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, MAX_FILTER_VALUES_PER_COLUMN);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String raw = resultSet.getString("value");
          if (raw == null || raw.isBlank()) {
            continue;
          }
          for (String token : normalizePhrase(raw).split(" ")) {
            if (!token.isBlank()) {
              values.add(token);
            }
          }
        }
      }
    } catch (Exception ex) {
      log.debug("Skipping runtime filter token generation for {}.{}",
          table.name(), column.name(), ex);
    }
    return new ArrayList<>(values);
  }

  private String qualifiedTableName(SchemaService.TableSchema table) {
    if (table.schemaName() == null || table.schemaName().isBlank()) {
      return quoteIdentifier(table.name());
    }
    return quoteIdentifier(table.schemaName()) + "." + quoteIdentifier(table.name());
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private String singularizePhrase(String value) {
    if (value.endsWith("ies") && value.length() > 4) {
      return value.substring(0, value.length() - 3) + "y";
    }
    if (value.endsWith("s") && value.length() > 3) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private String normalizePhrase(String value) {
    return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT))
        .replaceAll(" ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private void writeText(String relativePath, String content) {
    Path path = RuntimeResourceLoader.resolve(relativePath);
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new ResourceInitializationException("Failed to write runtime resource " + relativePath, ex);
    }
  }

  private void writeLines(String relativePath, List<String> lines) {
    writeText(relativePath, String.join(System.lineSeparator(), lines));
  }

  private List<String> loadBundledLines(String resourcePath) {
    try {
      return new ClassPathResource(resourcePath)
          .getContentAsString(StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .toList();
    } catch (IOException ex) {
      throw new ResourceInitializationException("Failed to read bundled resource " + resourcePath, ex);
    }
  }
}
