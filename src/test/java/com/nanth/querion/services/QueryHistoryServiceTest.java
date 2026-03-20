package com.nanth.querion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nanth.querion.dtos.FeedbackDto;
import com.nanth.querion.exceptions.InvalidQueryException;
import com.nanth.querion.models.QueryHistory;
import com.nanth.querion.repo.QueryHistoryRepo;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryHistoryServiceTest {

  @Mock
  private QueryHistoryRepo queryHistoryRepo;

  @InjectMocks
  private QueryHistoryService queryHistoryService;

  @Test
  void appliesNegativeFeedbackOnceAndDropsScoreAggressively() {
    QueryHistory history = new QueryHistory();
    history.setRequestId("req-1");
    history.setScore(1.5);
    history.setFeedbackCount(0);
    history.setPositiveFeedbackCount(0);
    history.setNegativeFeedbackCount(0);

    when(queryHistoryRepo.findByRequestId("req-1")).thenReturn(Optional.of(history));

    FeedbackDto feedback = queryHistoryService.applyFeedback("req-1", false);

    assertThat(feedback.isCorrect()).isFalse();
    assertThat(feedback.getScore()).isEqualTo(-1.5);
    assertThat(history.getUserFeedback()).isFalse();
    assertThat(history.getFeedbackCount()).isEqualTo(1);
    assertThat(history.getNegativeFeedbackCount()).isEqualTo(1);
    verify(queryHistoryRepo).save(history);
  }

  @Test
  void rejectsSecondFeedbackSubmissionForSameRequest() {
    QueryHistory history = new QueryHistory();
    history.setRequestId("req-2");
    history.setScore(1.5);
    history.setUserFeedback(true);

    when(queryHistoryRepo.findByRequestId("req-2")).thenReturn(Optional.of(history));

    assertThatThrownBy(() -> queryHistoryService.applyFeedback("req-2", false))
        .isInstanceOf(InvalidQueryException.class)
        .hasMessage("Feedback has already been submitted for this request.");

    verify(queryHistoryRepo, never()).save(history);
  }
}
