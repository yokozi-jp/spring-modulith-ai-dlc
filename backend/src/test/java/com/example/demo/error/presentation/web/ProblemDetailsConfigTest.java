package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.demo.testkit.TracerStubs;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;

/** 応答を書く直前の Problem Details に、要求の trace ID を付けることを検証する。 */
class ProblemDetailsConfigTest {

  /** 有効な trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  @AfterEach
  void resetRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("要求の observation の span の trace ID を traceId に入れる")
  void addsRequestTraceId() {
    bindRequestWithTraceId(TRACE_ID);
    final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

    intercept(problem);

    assertEquals(
        Map.of(ProblemDetailsConfig.TRACE_ID, TRACE_ID),
        problem.getProperties(),
        "traceId だけの拡張メンバー");
  }

  @Test
  @DisplayName("trace ID がすべて 0 のとき、traceId を付けない")
  void omitsInvalidTraceId() {
    bindRequestWithTraceId("00000000000000000000000000000000");
    final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

    intercept(problem);

    assertNull(problem.getProperties(), "すべて 0 の trace ID のときの拡張メンバー");
  }

  @Test
  @DisplayName("要求に observation がないとき、traceId を付けない")
  void omitsTraceIdWithoutObservation() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

    intercept(problem);

    assertNull(problem.getProperties(), "observation のない要求の拡張メンバー");
  }

  private static void intercept(final ProblemDetail problem) {
    final List<ErrorResponse.Interceptor> interceptors = new ArrayList<>();
    new ProblemDetailsConfig().addErrorResponseInterceptors(interceptors);
    interceptors.forEach(interceptor -> interceptor.handleError(problem, null));
  }

  private static void bindRequestWithTraceId(final String traceId) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final ServerRequestObservationContext observation =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());
    observation.put(TracingContext.class, tracingContext(traceId));
    request.setAttribute(
        ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private static TracingContext tracingContext(final String traceId) {
    final TracingContext tracing = new TracingContext();
    tracing.setSpan(TracerStubs.spanWithTraceId(traceId));
    return tracing;
  }
}
