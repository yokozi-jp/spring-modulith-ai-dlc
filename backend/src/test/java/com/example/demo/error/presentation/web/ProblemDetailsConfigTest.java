package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;
import tools.jackson.databind.json.JsonMapper;

/** 応答を書く直前の Problem Details に、要求の trace ID を付け、instance を消すことを検証する。 */
class ProblemDetailsConfigTest {

  /** 有効な trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  @AfterEach
  void resetRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("要求の observation の span の trace ID を traceId に入れ、instance を消す")
  void addsRequestTraceIdAndRemovesInstance() {
    bindRequestWithTraceId(TRACE_ID);
    final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setInstance(URI.create("/error"));

    intercept(problem);

    assertEquals(
        Map.of(ProblemDetailsConfig.TRACE_ID, TRACE_ID),
        problem.getProperties(),
        "traceId だけの拡張メンバー");
    assertNull(problem.getInstance(), "instance");
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

  @Test
  @DisplayName("要求の外で呼ばれても、traceId を付けずに instance を消す")
  void omitsTraceIdOutsideRequest() {
    final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setInstance(URI.create("/error"));

    intercept(problem);

    assertNull(problem.getProperties(), "要求の外の拡張メンバー");
    assertNull(problem.getInstance(), "instance");
  }

  @Test
  @DisplayName("Spring Framework は handler が返した Problem Details の空の instance に要求のパスを入れる")
  void springFillsEmptyInstanceWithRequestPath() throws Exception {
    final String body =
        MockMvcBuilders.standaloneSetup(new EmptyInstanceHandler())
            .build()
            .perform(get("/fixture"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(
        "/fixture",
        JsonMapper.builder().build().readTree(body).path("instance").asString(),
        "Spring Framework が instance を入れなくなった（7.1、spring-framework#35969）。"
            + "ProblemDetailsConfig の setInstance(null) と ADR-117 の Spring Framework 7.0 の注記を消す");
  }

  private static void intercept(final ProblemDetail problem) {
    final List<ErrorResponse.Interceptor> interceptors = new ArrayList<>();
    new ProblemDetailsConfig().addErrorResponseInterceptors(interceptors);
    interceptors.forEach(interceptor -> interceptor.handleError(problem, null));
  }

  // TracingContext の close は開いた scope を閉じるだけで、scope を開かないこのテストでは要らない。
  @SuppressWarnings("PMD.CloseResource")
  private static void bindRequestWithTraceId(final String traceId) {
    final TraceContext traceContext = Mockito.mock(TraceContext.class);
    Mockito.when(traceContext.traceId()).thenReturn(traceId);
    final Span span = Mockito.mock(Span.class);
    Mockito.when(span.context()).thenReturn(traceContext);
    final TracingContext tracing = new TracingContext();
    tracing.setSpan(span);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final ServerRequestObservationContext observation =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());
    observation.put(TracingContext.class, tracing);
    request.setAttribute(
        ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  /** 空の instance の Problem Details を返す handler。 */
  @Controller
  /* package */ static final class EmptyInstanceHandler {

    @GetMapping("/fixture")
    /* package */ ProblemDetail problem() {
      return ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    }
  }
}
