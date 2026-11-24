package com.example.demo.error.presentation.web;

import com.example.demo.DemoApplication;
import com.example.demo.shared.failure.FailureReason;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import io.opentelemetry.api.trace.TraceId;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * どの Problem Details にも、要求の trace ID を traceId で付け、instance を設定しない（ADR-117）。
 *
 * <p>OpenAPI の {@code ProblemDetail.type} に、応答が返す problem type を載せる（ADR-125）。
 */
@Configuration(proxyBeanMethods = false)
class ProblemDetailsConfig implements WebMvcConfigurer {

  /** 問い合わせとログを結ぶ trace ID を入れる拡張メンバー。 */
  /* package */ static final String TRACE_ID = "traceId";

  /** OpenApiConfig が登録する RFC 9457 の共通 schema の名前。 */
  private static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";

  /** 応答が返す problem type を、{@code ProblemDetail.type} の x-extensible-enum に載せる。 */
  @Bean
  /* package */ OpenApiCustomizer problemTypesInOpenApi() {
    return openApi -> {
      final Schema<?> problemDetail =
          Objects.requireNonNull(
              openApi.getComponents().getSchemas().get(PROBLEM_DETAIL_SCHEMA),
              PROBLEM_DETAIL_SCHEMA);
      final Schema<?> type =
          Objects.requireNonNull(problemDetail.getProperties().get("type"), "type");
      type.addExtension("x-extensible-enum", ApiProblemDetails.problemTypes(failureReasons()));
    };
  }

  /** ベースパッケージの下で {@link FailureReason} を実装する enum の定数を、すべて走査して返す。 */
  private static List<FailureReason> failureReasons() {
    final ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(FailureReason.class));
    return scanner.findCandidateComponents(DemoApplication.class.getPackageName()).stream()
        .map(BeanDefinition::getBeanClassName)
        .map(name -> ClassUtils.resolveClassName(Objects.requireNonNull(name), null))
        .filter(Class::isEnum)
        .flatMap(type -> Arrays.stream(type.getEnumConstants()))
        .map(FailureReason.class::cast)
        .toList();
  }

  /** 応答を書く直前の Problem Details を整える。 */
  @Override
  public void addErrorResponseInterceptors(final List<ErrorResponse.Interceptor> interceptors) {
    interceptors.add((detail, errorResponse) -> {
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
