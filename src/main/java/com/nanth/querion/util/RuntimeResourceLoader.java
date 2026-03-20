package com.nanth.querion.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;

public final class RuntimeResourceLoader {

  private static final Path BASE_DIRECTORY = Path.of(".runtime-resources");

  private RuntimeResourceLoader() {
  }

  public static Path resolve(String relativePath) {
    return BASE_DIRECTORY.resolve(relativePath);
  }

  public static List<String> loadLines(String relativePath) throws IOException {
    Path runtimePath = resolve(relativePath);
    if (Files.exists(runtimePath)) {
      return Files.readAllLines(runtimePath, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .toList();
    }

    return new ClassPathResource(relativePath)
        .getContentAsString(StandardCharsets.UTF_8)
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .toList();
  }

  public static Map<String, String> loadProperties(String relativePath) throws IOException {
    Properties properties = new Properties();
    Path runtimePath = resolve(relativePath);

    if (Files.exists(runtimePath)) {
      try (InputStream inputStream = Files.newInputStream(runtimePath)) {
        properties.load(inputStream);
      }
    } else {
      try (InputStream inputStream = new ClassPathResource(relativePath).getInputStream()) {
        properties.load(inputStream);
      }
    }

    Map<String, String> values = new LinkedHashMap<>();
    for (String name : properties.stringPropertyNames()) {
      values.put(name.trim().toLowerCase(Locale.ROOT),
          properties.getProperty(name).trim().toLowerCase(Locale.ROOT));
    }
    return Map.copyOf(values);
  }
}
