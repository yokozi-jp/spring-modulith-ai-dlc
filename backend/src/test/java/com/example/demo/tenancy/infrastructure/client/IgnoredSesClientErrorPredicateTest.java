package com.example.demo.tenancy.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/** メールの送信の circuit breaker が、スロットリング以外の 4xx だけを失敗に数えないことを検証する。 */
class IgnoredSesClientErrorPredicateTest {

  /** 検証する述語。 */
  private final IgnoredSesClientErrorPredicate predicate = new IgnoredSesClientErrorPredicate();

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "403, AccessDeniedException, true",
    "400, SendingPausedException, true",
    "404, NotFoundException, true",
    "429, TooManyRequestsException, false",
    "400, Throttling, false",
    "500, InternalFailure, false",
    "503, ServiceUnavailable, false",
  })
  @DisplayName("SES の 4xx は、スロットリングを除いて失敗に数えない")
  void ignoresClientErrorsExceptThrottling(
      final int status, final String errorCode, final boolean ignored) {
    final SesV2Exception exception =
        (SesV2Exception)
            SesV2Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .build();

    assertThat(predicate.test(exception)).as("%d %s を数えないか", status, errorCode).isEqualTo(ignored);
  }

  @Test
  @DisplayName("接続の失敗やタイムアウトの SdkClientException は、失敗に数える")
  void countsClientSideFailures() {
    assertThat(predicate.test(SdkClientException.create("connection refused")))
        .as("SdkClientException を数えないか")
        .isFalse();
  }
}
