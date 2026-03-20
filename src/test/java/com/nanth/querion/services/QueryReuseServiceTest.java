package com.nanth.querion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nanth.querion.models.QueryHistory;
import com.nanth.querion.repo.QueryHistoryRepo;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryReuseServiceTest {

  @Mock
  private QueryHistoryRepo queryHistoryRepo;

  @Test
  void findsExactNormalizedMatchBeforeLlmCall() {
    QueryReuseService service = new QueryReuseService(queryHistoryRepo);
    QueryHistory history = new QueryHistory();
    history.setUserQuery("Get customer first name, lastname, email and company and document url for document impl status PENDING");

    when(queryHistoryRepo.findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
        "DATA", "SUCCESS", 1.0))
        .thenReturn(List.of(history));

    Optional<QueryReuseMatch> match = service.findReusableQuery(
        "get customer first name lastname email and company and document url for document implementation status pending");

    assertThat(match).isPresent();
    assertThat(match.get().strategy()).isEqualTo("normalized");
    assertThat(match.get().history()).isSameAs(history);
    assertThat(match.get().params()).isEmpty();
  }

  @Test
  void findsSemanticMatchForCloseParaphrase() {
    QueryReuseService service = new QueryReuseService(queryHistoryRepo);
    QueryHistory history = new QueryHistory();
    history.setUserQuery("show customer first name last name email company and document url for pending implementation status");

    when(queryHistoryRepo.findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
        "DATA", "SUCCESS", 1.0))
        .thenReturn(List.of(history));

    Optional<QueryReuseMatch> match = service.findReusableQuery(
        "fetch customer firstname lastname email company with document url where document impl status pending");

    assertThat(match).isPresent();
    assertThat(match.get().strategy()).isEqualTo("semantic");
    assertThat(match.get().similarityScore()).isGreaterThan(0.83);
    assertThat(match.get().params()).isEmpty();
  }

  @Test
  void findsTemplateMatchWhenOnlySingleFilterValueChanges() {
    QueryReuseService service = new QueryReuseService(queryHistoryRepo);
    QueryHistory history = new QueryHistory();
    history.setUserQuery("get customer with name amit");
    history.setGeneratedSql("SELECT c.email FROM customer c WHERE c.name ILIKE :customerName");
    history.setGeneratedSqlParams("{\"customerName\":\"%Amit%\"}");

    when(queryHistoryRepo.findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
        "DATA", "SUCCESS", 1.0))
        .thenReturn(List.of(history));

    Optional<QueryReuseMatch> match = service.findReusableQuery("get customer with name rahul");

    assertThat(match).isPresent();
    assertThat(match.get().strategy()).isEqualTo("template");
    assertThat(match.get().params()).containsEntry("customerName", "%rahul%");
  }

  @Test
  void findsTemplateMatchWhenMultipleFilterValuesChange() {
    QueryReuseService service = new QueryReuseService(queryHistoryRepo);
    QueryHistory history = new QueryHistory();
    history.setUserQuery("get invoice for customer amit with status unpaid");
    history.setGeneratedSql(
        "SELECT i.id, i.amount FROM invoice i JOIN customer c ON i.customer_id = c.id "
            + "WHERE c.name ILIKE :customerName AND i.status = :status");
    history.setGeneratedSqlParams("{\"customerName\":\"%Amit%\",\"status\":\"unpaid\"}");

    when(queryHistoryRepo.findTop50ByQueryTypeAndExecutionStatusAndScoreGreaterThanEqualOrderByScoreDescCreatedAtDesc(
        "DATA", "SUCCESS", 1.0))
        .thenReturn(List.of(history));

    Optional<QueryReuseMatch> match =
        service.findReusableQuery("get invoice for customer rahul with status paid");

    assertThat(match).isPresent();
    assertThat(match.get().strategy()).isEqualTo("template");
    assertThat(match.get().params())
        .containsEntry("customerName", "%rahul%")
        .containsEntry("status", "paid");
  }
}
