package com.nanth.querion.repo;

import com.nanth.querion.models.QueryHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QueryHistoryRepo extends JpaRepository<QueryHistory, Long> {

  List<QueryHistory> findTop10ByOrderByCreatedAtDesc();

  List<QueryHistory> findTop50ByQueryTypeAndExecutionStatusOrderByCreatedAtDesc(
      String queryType,
      String executionStatus);

  List<QueryHistory> findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
      String queryType,
      String executionStatus,
      Double score);

  Optional<QueryHistory> findTopByQueryTypeOrderByCreatedAtDesc(String queryType);

  long countByQueryType(String queryType);

  long countByQueryTypeAndExecutionStatus(String queryType, String executionStatus);

  long countByExecutionStatus(String executionStatus);

  Optional<QueryHistory> findByRequestId(String requestId);
}
