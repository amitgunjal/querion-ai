package com.nanth.querion.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanth.querion.dtos.OllamaResponse;
import com.nanth.querion.exceptions.ExternalServiceException;
import com.nanth.querion.models.Llamma;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class ApiService {
    private static final Logger log = LoggerFactory.getLogger(ApiService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    @Value("${ollama.keep-alive:10m}")
    private String ollamaKeepAlive;

    public ApiService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public OllamaResponse query(Llamma llamma) {
        return query(llamma, null);
    }

    public OllamaResponse query(Llamma llamma, Consumer<String> chunkConsumer) {
        if (llamma == null || llamma.getApiUrl() == null || llamma.getApiUrl().isBlank()) {
            throw new ExternalServiceException("LLM API configuration is missing.");
        }

        Map<String, Object> request = new HashMap<>();
        request.put("model", llamma.getModel());
        request.put("prompt", llamma.getPrompt());
        request.put("stream", llamma.isStream());
        request.put("keep_alive", ollamaKeepAlive);

        HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        try {
            long startedAt = System.nanoTime();
            if (llamma.isStream()) {
                OllamaResponse streamedResponse = restTemplate.execute(
                        llamma.getApiUrl(),
                        HttpMethod.POST,
                        requestCallback -> {
                            requestCallback.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            objectMapper.writeValue(requestCallback.getBody(), request);
                        },
                        response -> consumeStream(response, startedAt, chunkConsumer)
                );
                if (streamedResponse == null || streamedResponse.getResponse() == null) {
                    throw new ExternalServiceException("LLM API returned an empty or unsuccessful response.");
                }
                return streamedResponse;
            }

            ResponseEntity<OllamaResponse> response =
                    restTemplate.postForEntity(llamma.getApiUrl(), entity, OllamaResponse.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new ExternalServiceException("LLM API returned an empty or unsuccessful response.");
            }
            response.getBody().setApiResponseTimeMs((System.nanoTime() - startedAt) / 1_000_000);
            return response.getBody();
        } catch (ResourceAccessException ex) {
            log.error("Timed out or could not reach LLM API at {}", llamma.getApiUrl(), ex);
            throw new ExternalServiceException(
                    "Timed out while waiting for the LLM API. The app now keeps Ollama warm, but you may still need a higher ollama.read-timeout-seconds or a smaller model.",
                    ex);
        } catch (RestClientException ex) {
            log.error("Failed calling LLM API at {}", llamma.getApiUrl(), ex);
            throw new ExternalServiceException("Failed to communicate with the LLM API.", ex);
        }
    }

    private OllamaResponse consumeStream(
            ClientHttpResponse response,
            long startedAt,
            Consumer<String> chunkConsumer) throws IOException {
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new ExternalServiceException("LLM API returned an empty or unsuccessful response.");
        }

        StringBuilder fullResponse = new StringBuilder();
        OllamaResponse lastChunk = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }

                OllamaResponse chunk = objectMapper.readValue(trimmed, OllamaResponse.class);
                lastChunk = chunk;

                if (chunk.getResponse() != null && !chunk.getResponse().isEmpty()) {
                    fullResponse.append(chunk.getResponse());
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(chunk.getResponse());
                    }
                }
            }
        }

        if (lastChunk == null) {
            throw new ExternalServiceException("LLM API returned an empty stream.");
        }

        lastChunk.setResponse(fullResponse.toString());
        lastChunk.setApiResponseTimeMs((System.nanoTime() - startedAt) / 1_000_000);
        return lastChunk;
    }
}
