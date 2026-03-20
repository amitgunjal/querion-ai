package com.nanth.querion.engine;

import com.nanth.querion.exceptions.ResourceInitializationException;
import com.nanth.querion.util.RuntimeResourceLoader;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("runtimeVocabularyGenerator")
public class ClassifierBrain {

  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
  private static final int DATA_LABEL = 1;
  private static final int CHAT_LABEL = 0;

  private final Brain brain = new Brain();
  private final Set<String> chatPhrases;
  private final Set<String> dataVerbs;
  private final Set<String> dataNouns;
  private final Set<String> filterWords;
  private final List<String> chatSeedSamples;
  private final List<String> dataSeedSamples;

  public ClassifierBrain() {
    this.chatPhrases = loadWordSet("classifier/chat-phrases.txt");
    this.dataVerbs = loadWordSet("classifier/data-verbs.txt");
    this.dataNouns = loadWordSet("classifier/data-nouns.txt");
    this.filterWords = loadWordSet("classifier/filter-words.txt");
    this.chatSeedSamples = loadLines("classifier/chat-seed-samples.txt");
    this.dataSeedSamples = loadLines("classifier/data-seed-samples.txt");
  }

  @PostConstruct
  public void train() {
    List<String> texts = new ArrayList<>();
    List<Integer> labels = new ArrayList<>();

    addSeedData(texts, labels);

    if (!texts.isEmpty()) {
      train(texts.toArray(new String[0]), labels.stream().mapToInt(Integer::intValue).toArray());
    }
  }

  public boolean isDataQuery(String text) {
    String normalized = normalize(text);
    if (normalized.isBlank()) {
      return false;
    }

    double[] features = features(normalized);
    double result = brain.predict(features);
    return result >= 0.55;
  }

  public void trainFromHistory() {
    // History retraining is intentionally disabled until verified labels are available.
    train();
  }

  private void addSeedData(List<String> texts, List<Integer> labels) {
    addSamples(texts, labels, CHAT_LABEL, chatSeedSamples);
    addSamples(texts, labels, DATA_LABEL, dataSeedSamples);
  }

  private void addSamples(List<String> texts, List<Integer> labels, int label, List<String> samples) {
    for (String sample : samples) {
      texts.add(sample);
      labels.add(label);
    }
  }

  private void train(String[] texts, int[] labels) {
    double[][] trainingData = new double[texts.length][];
    for (int i = 0; i < texts.length; i++) {
      trainingData[i] = features(normalize(texts[i]));
    }
    brain.train(trainingData, labels, 1500, 0.08);
  }

  private double[] features(String text) {
    String[] tokens = text.split(" ");
    int greetingMatches = countPhraseMatches(text, chatPhrases);
    int dataVerbMatches = countTokenMatches(tokens, dataVerbs);
    int dataNounMatches = countTokenMatches(tokens, dataNouns);
    int filterMatches = countTokenMatches(tokens, filterWords);
    int tokenCount = tokens.length;
    int startsWithDataVerb = tokens.length > 0 && dataVerbs.contains(tokens[0]) ? 1 : 0;

    return new double[]{
        greetingMatches,
        dataVerbMatches,
        dataNounMatches,
        filterMatches,
        tokenCount,
        startsWithDataVerb
    };
  }

  private int countPhraseMatches(String text, Set<String> phrases) {
    int count = 0;
    for (String phrase : phrases) {
      Pattern phrasePattern = Pattern.compile("(^|\\s)" + Pattern.quote(phrase) + "(\\s|$)");
      if (phrasePattern.matcher(text).find()) {
        count++;
      }
    }
    return count;
  }

  private int countTokenMatches(String[] tokens, Set<String> dictionary) {
    int count = 0;
    for (String token : tokens) {
      if (dictionary.contains(token)) {
        count++;
      }
    }
    return count;
  }

  private String normalize(String text) {
    if (text == null) {
      return "";
    }
    String cleaned = NON_ALPHANUMERIC.matcher(text.toLowerCase()).replaceAll(" ");
    return MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();
  }

  private Set<String> loadWordSet(String resourcePath) {
    return Set.copyOf(loadLines(resourcePath));
  }

  private List<String> loadLines(String resourcePath) {
    try {
      return RuntimeResourceLoader.loadLines(resourcePath);
    } catch (IOException ex) {
      throw new ResourceInitializationException(
          "Failed to load classifier vocabulary from " + resourcePath, ex);
    }
  }
}
