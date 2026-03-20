package com.nanth.querion.services;


import com.nanth.querion.exceptions.ResourceInitializationException;
import com.nanth.querion.util.RuntimeResourceLoader;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

@Service
@DependsOn("runtimeVocabularyGenerator")
public class GreetingService {

  private static final Pattern OPENING_PATTERN = Pattern.compile("^(Hello|Hi there|Hi|Hey there|Hey)!?\\s*");
  private static final Pattern THANKS_PATTERN = Pattern.compile("\\b(thanks|thank you|thx)\\b");
  private static final Pattern HELP_PATTERN = Pattern.compile("\\b(help|what can you do|how can you help)\\b");
  private static final Pattern IDENTITY_PATTERN = Pattern.compile("\\b(who are you|what are you)\\b");
  private static final Pattern WELLBEING_PATTERN = Pattern.compile("\\b(how are you|how are you doing)\\b");
  private static final Pattern GREETING_PATTERN = Pattern.compile(
      "\\b(hi|hello|hey|good morning|good afternoon|good evening|whats up|what is up)\\b");

  private final List<String> greetings = new ArrayList<>();
  private final List<String> helpExamples = new ArrayList<>();
  private final Random random = new Random();

  public GreetingService() {
    loadLines("greetings.txt", greetings, "Failed to load greeting messages.");
    loadLines("greeting-help-examples.txt", helpExamples, "Failed to load greeting help examples.");

    if (greetings.isEmpty()) {
      throw new ResourceInitializationException("Greeting messages are empty.", null);
    }
  }

  public String randomGreeting() {
    return greetings.get(random.nextInt(greetings.size()));
  }

  public String chatReply(String userQuery) {
    String normalized = normalize(userQuery);
    if (normalized.isBlank()) {
      return timeAwareGreeting();
    }

    if (THANKS_PATTERN.matcher(normalized).find()) {
      return randomFrom(
          "You're welcome. Ask me anything about your data when you're ready.",
          "Happy to help. If you want, ask me about your records next.",
          "Anytime. I can help with your data questions whenever you're ready."
      );
    }

    if (HELP_PATTERN.matcher(normalized).find()) {
      return randomFrom(
          timeAwareGreeting() + " I can help answer questions about your data and records.",
          timeAwareGreeting() + " Try asking me to list, count, find, or show records.",
          timeAwareGreeting() + " You can ask things like " + String.join(", ", helpExamples) + "."
      );
    }

    if (IDENTITY_PATTERN.matcher(normalized).find()) {
      return randomFrom(
          timeAwareGreeting() + " I'm Querion, your data query assistant.",
          timeAwareGreeting() + " I'm Querion. I help turn your questions into answers from your data.",
          timeAwareGreeting() + " I'm Querion, here to help you explore your records."
      );
    }

    if (WELLBEING_PATTERN.matcher(normalized).find()) {
      return randomFrom(
          timeAwareGreeting() + " I'm here and ready to help with your data questions.",
          timeAwareGreeting() + " I'm doing well and ready to help you explore your data.",
          timeAwareGreeting() + " I'm ready to help. Ask me about your records anytime."
      );
    }

    if (GREETING_PATTERN.matcher(normalized).find()) {
      return timeAwareGreeting();
    }

    return randomFrom(
        timeAwareGreeting() + " I can help with your data-related questions.",
        timeAwareGreeting() + " Try asking me to show, count, list, or find records.",
        timeAwareGreeting() + " I can help you explore your records if you tell me what you need."
    );
  }

  public String timeAwareGreeting() {
    String greeting = randomGreeting();
    String timeGreeting = currentTimeGreeting();
    if (OPENING_PATTERN.matcher(greeting).find()) {
      return OPENING_PATTERN.matcher(greeting).replaceFirst(timeGreeting + "! ");
    }

    return timeGreeting + "! " + greeting;
  }

  private String currentTimeGreeting() {
    LocalTime now = LocalTime.now();
    if (now.isBefore(LocalTime.NOON)) {
      return "Good morning";
    }
    if (now.isBefore(LocalTime.of(17, 0))) {
      return "Good afternoon";
    }
    return "Good evening";
  }

  private String randomFrom(String... responses) {
    return responses[random.nextInt(responses.length)];
  }

  private String normalize(String userQuery) {
    if (userQuery == null) {
      return "";
    }
    return userQuery.toLowerCase().trim();
  }

  private void loadLines(String resourcePath, List<String> target, String errorMessage) {
    try {
      target.addAll(RuntimeResourceLoader.loadLines(resourcePath));
    } catch (IOException ex) {
      throw new ResourceInitializationException(errorMessage, ex);
    }
  }
}
