package com.nanth.querion.models;

import com.nanth.querion.dtos.OllamaResponse;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Llamma {
    private String userQuery;
    private String model;
    private String apiUrl;
    private boolean stream;
    private String prompt;
    private OllamaResponse response;
}
