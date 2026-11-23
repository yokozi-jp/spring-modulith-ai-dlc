package com.example.demo.error.presentation.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** どの Problem Details にも instance を出さない（ADR-117）。 */
@Configuration(proxyBeanMethods = false)
class ProblemDetailsConfig implements WebMvcConfigurer {

  /**
   * 応答を書く直前の Problem Details から instance を消す。
   *
   * <p>Spring Framework 7.0 は handler が返した後で、空の instance に要求のパスを入れ、その後でこの interceptor を呼ぶ。 7.1 は
   * instance を入れなくなる（spring-framework#35969）ので、7.1 へ上げたらこのクラスを消す。
   */
  @Override
  public void addErrorResponseInterceptors(final List<ErrorResponse.Interceptor> interceptors) {
    interceptors.add((detail, errorResponse) -> detail.setInstance(null));
  }
}
