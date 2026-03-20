package com.nanth.querion.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanth.querion.models.QueryHistory;
import com.nanth.querion.repo.QueryHistoryRepo;
import com.nanth.querion.util.RuntimeResourceLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("runtimeVocabularyGenerator")
public class QueryReuseService {

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
  };
  private static final double SEMANTIC_THRESHOLD = 0.83;
  private static final double MIN_REUSE_SCORE = 1.0;

  private final QueryHistoryRepo queryHistoryRepo;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, String> synonyms;
  private final Set<String> stopWords;
  private final Set<String> filterValueTokens;

  public QueryReuseService(QueryHistoryRepo queryHistoryRepo) {
    this.queryHistoryRepo = queryHistoryRepo;
    this.synonyms = loadSynonyms("query-reuse/synonyms.properties");
    this.stopWords = loadWordSet("query-reuse/stop-words.txt");
    this.filterValueTokens = loadWordSet("query-reuse/filter-value-tokens.txt");
  }

  public Optional<QueryReuseMatch> findReusableQuery(String userQuery) {
    String normalized = normalize(userQuery);
    if (normalized.isBlank()) {
      return Optional.empty();
    }

    List<QueryHistory> candidates =
        queryHistoryRepo.findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
            "DATA", "SUCCESS", MIN_REUSE_SCORE);

    Optional<QueryHistory> exactMatch = candidates.stream()
        .filter(item -> normalize(item.getUserQuery()).equals(normalized))
        .findFirst();
    if (exactMatch.isPresent()) {
      return Optional.of(new QueryReuseMatch(exactMatch.get(), "normalized", 1.0, Map.of()));
    }

    Optional<QueryReuseMatch> templateMatch = candidates.stream()
        .map(candidate -> buildTemplateMatch(candidate, normalized))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst();
    if (templateMatch.isPresent()) {
      return templateMatch;
    }

    List<String> queryTokens = meaningfulTokens(normalized);
    if (queryTokens.isEmpty()) {
      return Optional.empty();
    }

    Set<String> queryFilterTokens = extractFilterValueTokens(queryTokens);
    return candidates.stream()
        .map(candidate -> scoreCandidate(candidate, normalized, queryTokens, queryFilterTokens))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .max(Comparator.comparingDouble(QueryReuseMatch::similarityScore))
        .filter(match -> match.similarityScore() >= SEMANTIC_THRESHOLD);
  }

  private Optional<QueryReuseMatch> scoreCandidate(QueryHistory candidate, String normalizedQuery,
      List<String> queryTokens, Set<String> queryFilterTokens) {
    String candidateNormalized = normalize(candidate.getUserQuery());
    if (candidateNormalized.isBlank() || candidateNormalized.equals(normalizedQuery)) {
      return Optional.empty();
    }

    List<String> candidateTokens = meaningfulTokens(candidateNormalized);
    if (candidateTokens.isEmpty()) {
      return Optional.empty();
    }

    if (!queryFilterTokens.isEmpty()
        && !extractFilterValueTokens(candidateTokens).containsAll(queryFilterTokens)) {
      return Optional.empty();
    }

    double cosine = cosineSimilarity(queryTokens, candidateTokens);
    double overlap = overlapScore(queryTokens, candidateTokens);
    double score = (cosine * 0.7) + (overlap * 0.3);

    if (sharedTokenCount(queryTokens, candidateTokens) < 3) {
      return Optional.empty();
    }

    return Optional.of(new QueryReuseMatch(candidate, "semantic", score, Map.of()));
  }

  private String normalize(String text) {
    if (text == null) {
      return "";
    }
    String cleaned = NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
    String normalized = MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();

    for (Map.Entry<String, String> entry : synonyms.entrySet()) {
      normalized = normalized.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b", entry.getValue());
    }

    return MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
  }

  private List<String> meaningfulTokens(String normalizedQuery) {
    List<String> tokens = new ArrayList<>();
    for (String token : normalizedQuery.split(" ")) {
      if (token.isBlank() || stopWords.contains(token)) {
        continue;
      }
      tokens.add(stem(token));
    }
    return tokens;
  }

  private String stem(String token) {
    if (token.endsWith("ies") && token.length() > 4) {
      return token.substring(0, token.length() - 3) + "y";
    }
    if (token.endsWith("s") && token.length() > 4) {
      return token.substring(0, token.length() - 1);
    }
    return token;
  }

  private Set<String> extractFilterValueTokens(List<String> tokens) {
    Set<String> values = new HashSet<>();
    for (String token : tokens) {
      if (filterValueTokens.contains(token) || token.chars().allMatch(Character::isDigit)) {
        values.add(token);
      }
    }
    return values;
  }

  private int sharedTokenCount(List<String> left, List<String> right) {
    Set<String> shared = new HashSet<>(left);
    shared.retainAll(new HashSet<>(right));
    return shared.size();
  }

  private double overlapScore(List<String> left, List<String> right) {
    Set<String> leftSet = new HashSet<>(left);
    Set<String> rightSet = new HashSet<>(right);
    leftSet.retainAll(rightSet);
    int denominator = Math.max(left.size(), right.size());
    if (denominator == 0) {
      return 0.0;
    }
    return (double) leftSet.size() / denominator;
  }

  private double cosineSimilarity(List<String> left, List<String> right) {
    Map<String, Integer> leftFreq = frequencyMap(left);
    Map<String, Integer> rightFreq = frequencyMap(right);

    double dot = 0.0;
    double leftNorm = 0.0;
    double rightNorm = 0.0;

    for (Map.Entry<String, Integer> entry : leftFreq.entrySet()) {
      int leftValue = entry.getValue();
      int rightValue = rightFreq.getOrDefault(entry.getKey(), 0);
      dot += (double) leftValue * rightValue;
      leftNorm += (double) leftValue * leftValue;
    }

    for (int value : rightFreq.values()) {
      rightNorm += (double) value * value;
    }

    if (leftNorm == 0.0 || rightNorm == 0.0) {
      return 0.0;
    }

    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  private Map<String, Integer> frequencyMap(List<String> tokens) {
    Map<String, Integer> frequencies = new HashMap<>();
    for (String token : tokens) {
      frequencies.merge(token, 1, Integer::sum);
    }
    return frequencies;
  }

  private Optional<QueryReuseMatch> buildTemplateMatch(QueryHistory candidate, String normalizedQuery) {
    if (candidate == null || candidate.getGeneratedSql() == null || candidate.getGeneratedSql().isBlank()) {
      return Optional.empty();
    }

    Map<String, Object> storedParams = parseParams(candidate.getGeneratedSqlParams());
    if (storedParams.isEmpty()) {
      return Optional.empty();
    }

    String candidateNormalized = normalize(candidate.getUserQuery());
    if (candidateNormalized.isBlank()) {
      return Optional.empty();
    }

    List<ParamBinding> bindings = buildBindings(candidateNormalized, storedParams);
    if (bindings.isEmpty()) {
      return Optional.empty();
    }

    String regex = buildTemplateRegex(candidateNormalized, bindings);
    java.util.regex.Matcher matcher = Pattern.compile(regex).matcher(normalizedQuery);
    if (!matcher.matches()) {
      return Optional.empty();
    }

    boolean changed = false;
    Map<String, Object> params = new LinkedHashMap<>();
    for (int i = 0; i < bindings.size(); i++) {
      ParamBinding binding = bindings.get(i);
      String extractedValue = matcher.group(i + 1) == null ? "" : matcher.group(i + 1).trim();
      if (extractedValue.isBlank()) {
        return Optional.empty();
      }
      if (!extractedValue.equals(binding.normalizedValue())) {
        changed = true;
      }
      params.put(binding.key(), rebuildParamValue(binding.originalValue(), extractedValue));
    }

    if (!changed) {
      return Optional.empty();
    }

    return Optional.of(new QueryReuseMatch(candidate, "template", 0.99, params));
  }

  private List<ParamBinding> buildBindings(String candidateNormalized, Map<String, Object> storedParams) {
    List<ParamBinding> bindings = storedParams.entrySet().stream()
        .map(entry -> {
          String normalizedValue = normalizeParamValue(entry.getValue());
          int start = normalizedValue.isBlank() ? -1 : candidateNormalized.indexOf(normalizedValue);
          return new ParamBinding(entry.getKey(), entry.getValue(), normalizedValue, start);
        })
        .filter(binding -> !binding.normalizedValue().isBlank() && binding.startIndex() >= 0)
        .sorted(Comparator.comparingInt(ParamBinding::startIndex)
            .thenComparing(binding -> -binding.normalizedValue().length()))
        .collect(Collectors.toList());

    List<ParamBinding> nonOverlapping = new ArrayList<>();
    int cursor = 0;
    for (ParamBinding binding : bindings) {
      if (binding.startIndex() < cursor) {
        continue;
      }
      nonOverlapping.add(binding);
      cursor = binding.startIndex() + binding.normalizedValue().length();
    }
    return nonOverlapping;
  }

  private String buildTemplateRegex(String candidateNormalized, List<ParamBinding> bindings) {
    StringBuilder regex = new StringBuilder("^");
    int cursor = 0;
    for (ParamBinding binding : bindings) {
      regex.append(Pattern.quote(candidateNormalized.substring(cursor, binding.startIndex())));
      regex.append("(.+?)");
      cursor = binding.startIndex() + binding.normalizedValue().length();
    }
    regex.append(Pattern.quote(candidateNormalized.substring(cursor)));
    regex.append("$");
    return regex.toString();
  }

  private Map<String, Object> parseParams(String rawParams) {
    if (rawParams == null || rawParams.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(rawParams, MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private String normalizeParamValue(Object value) {
    if (value == null) {
      return "";
    }
    String raw = String.valueOf(value).trim();
    if (raw.isBlank()) {
      return "";
    }

    String withoutWildcards = raw.replace("%", " ").replace("_", " ").trim();
    return normalize(withoutWildcards);
  }

  private Object rebuildParamValue(Object originalValue, String extractedValue) {
    if (originalValue instanceof Number) {
      String digitsOnly = extractedValue.replaceAll("[^0-9-]", "");
      if (digitsOnly.isBlank()) {
        return originalValue;
      }
      try {
        if (originalValue instanceof Integer) {
          return Integer.parseInt(digitsOnly);
        }
        if (originalValue instanceof Long) {
          return Long.parseLong(digitsOnly);
        }
        if (originalValue instanceof Double) {
          return Double.parseDouble(digitsOnly);
        }
      } catch (NumberFormatException ex) {
        return originalValue;
      }
    }

    String rawOriginal = String.valueOf(originalValue);
    String trimmedValue = extractedValue.trim();
    if (rawOriginal.startsWith("%") && rawOriginal.endsWith("%")) {
      return "%" + trimmedValue + "%";
    }
    if (rawOriginal.startsWith("%")) {
      return "%" + trimmedValue;
    }
    if (rawOriginal.endsWith("%")) {
      return trimmedValue + "%";
    }
    return trimmedValue;
  }

  private record ParamBinding(
      String key,
      Object originalValue,
      String normalizedValue,
      int startIndex
  ) {
  }

  private Map<String, String> loadSynonyms(String resourcePath) {
    try {
      return RuntimeResourceLoader.loadProperties(resourcePath);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load query reuse synonyms from " + resourcePath, ex);
    }
  }

  private Set<String> loadWordSet(String resourcePath) {
    try {
      List<String> lines = RuntimeResourceLoader.loadLines(resourcePath).stream()
          .map(line -> line.toLowerCase(Locale.ROOT))
          .toList();
      return Set.copyOf(lines);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load query reuse vocabulary from " + resourcePath, ex);
    }
  }
}
