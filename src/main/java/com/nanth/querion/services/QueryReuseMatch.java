package com.nanth.querion.services;

import com.nanth.querion.models.QueryHistory;
import java.util.Map;

public record QueryReuseMatch(
    QueryHistory history,
    String strategy,
    double similarityScore,
    Map<String, Object> params
) {
}
