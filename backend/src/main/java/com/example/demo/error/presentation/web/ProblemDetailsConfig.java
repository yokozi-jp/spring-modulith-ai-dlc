package com.example.demo.error.presentation.web;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import io.opentelemetry.api.trace.TraceId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** どの Problem Details にも、要求の trace ID を traceId で付け、instance を出さない（ADR-117）。 */
@Configuration(proxyBeanMethods = false)
class ProblemDetailsConfig implements WebMvcConfigurer {

  /** 問い合わせとログを結ぶ trace ID を入れる拡張メンバー。 */
  /* package */ static final String TRACE_ID = "traceId";

  /**
   * 応答を書く直前の Problem Details を整える。
   *
   * <p>Spring Framework 7.0 は handler が返した後で空の instance に要求のパスを入れ、その後でこの interceptor を呼ぶ。 instance
   * はどの handler の Problem Details でも消し、handler が外す手段はない。
   */
  @Override
  public void addErrorResponseInterceptors(final List<ErrorResponse.Interceptor> interceptors) {
    interceptors.add((detail, errorResponse) -> {
      detail.setInstance(null);
      requestTraceId().ifPresent(traceId -> detail.setProperty(TRACE_ID, traceId));
    });
  }

  /**
   * 現在の span ではなく、要求の属性に残る HTTP の observation の span の trace ID を返す（ADR-117）。
   *
   * <p>interceptor は Spring MVC の中か、RequestContextFilter の後の Spring Security の handler
   * からだけ呼ばれ、要求は束縛済みである。束縛がなければ例外になり、Spring Framework がそれを無視するので、traceId だけが付かない。
   */
  private static Optional<String> requestTraceId() {
    final HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    return ServerHttpObservationFilter.findObservationContext(request)
        .map(context -> context.<TracingContext>get(TracingContext.class))
        .map(TracingContext::getSpan)
        .map(span -> span.context().traceId())
        .filter(TraceId::isValid);
  }
}
