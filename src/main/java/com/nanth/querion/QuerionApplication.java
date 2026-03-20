package com.nanth.querion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableScheduling
public class QuerionApplication {

	@Value("${ollama.connect-timeout-seconds:5}")
	private long ollamaConnectTimeoutSeconds;

	@Value("${ollama.read-timeout-seconds:300}")
	private long ollamaReadTimeoutSeconds;

	public static void main(String[] args) {
		SpringApplication.run(QuerionApplication.class, args);
	}

	@Bean
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(ollamaConnectTimeoutSeconds));
		factory.setReadTimeout(Duration.ofSeconds(ollamaReadTimeoutSeconds));
		return new RestTemplate(factory);
	}

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}
}
