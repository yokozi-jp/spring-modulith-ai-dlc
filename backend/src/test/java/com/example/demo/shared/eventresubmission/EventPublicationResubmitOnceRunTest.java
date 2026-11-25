package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import java.time.Duration;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 手での 1 回の再投入の入口が、Sweep の結果を件数と終了コードの判定にどう写すか。 */
class EventPublicationResubmitOnceRunTest {

  @Test
  @DisplayName("選ばれても進まなかった出版は、件数にも incomplete にも入らず、終了コードを 0 にする")
  void rowsThatDidNotAdvanceDoNotPreventSuccess() {
    final FailedPublicationSweep sweep = Mockito.mock(FailedPublicationSweep.class);
    Mockito.when(sweep.waitUpTo(Mockito.any()))
        .thenReturn(new FailedPublicationSweep.Swept(Map.of(), 0));
    final EventPublicationResubmitOnce job =
        new EventPublicationResubmitOnce(
            Mockito.mock(DSLContext.class),
            sweep,
            Mockito.mock(ResubmissionPolicies.class),
            Mockito.mock(EventPublicationResubmissionConfig.Properties.class),
            new EventPublicationResubmissionConfig.ResubmitOnceProperties(
                "all", Duration.ofSeconds(1)));

    assertThat(job.run(new Target.All()))
        .isEqualTo(new EventPublicationResubmitOnce.Outcome(0, 0, 0, 0));
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "all")).isZero();
  }
}
