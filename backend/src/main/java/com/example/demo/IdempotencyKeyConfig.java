package com.example.demo;

import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotencyKeyException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Controller の {@link IdempotencyKey} の引数に、要求の {@code Idempotency-Key} ヘッダーから読んだキーを渡す（ADR-126）。
 *
 * <p>ヘッダーがないか、1 行の引用符で囲んだ UUID でなければ、{@link IdempotencyKeyException} を投げる。Spring MVC
 * は引数を宣言の順に解決するので、本文より前に置いたキーの誤りは、本文の検証より先に 400 になる。
 */
@Configuration(proxyBeanMethods = false)
public class IdempotencyKeyConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new HeaderResolver());
  }

  /** {@link IdempotencyKey} の引数を、要求のヘッダーの行から作る。 */
  private static final class HeaderResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
      return parameter.getParameterType() == IdempotencyKey.class;
    }

    @Override
    public Object resolveArgument(
        final MethodParameter parameter,
        final @Nullable ModelAndViewContainer mavContainer,
        final NativeWebRequest webRequest,
        final @Nullable WebDataBinderFactory binderFactory) {
      return IdempotencyKey.fromHeader(webRequest.getHeaderValues(IdempotencyKey.HEADER));
    }
  }
}
