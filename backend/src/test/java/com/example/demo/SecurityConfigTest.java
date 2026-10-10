package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.ModelAndView;

/**
 * API の entry point と access denied handler が、resolver の扱わなかった例外を sendError へ落とすことを確かめる。
 *
 * <p>本番の context では {@code ApiExceptionHandler} が認証と認可の例外を常に扱うため、null を返す resolver は Spring
 * を起動せずに渡す。sendError の後の {@code /error} 転送は {@code ApiContractTest} で確かめる。
 */
class SecurityConfigTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("securityExceptions")
  @DisplayName("resolver が扱わなかった例外は期待した status で sendError し、本文を書かない")
  void sendsErrorWhenNoResolverHandles(
      final String name, final Exception exception, final HttpStatus status) throws IOException {
    final MockHttpServletResponse response = new MockHttpServletResponse();

    SecurityConfig.resolveOrSendError(
        (request, res, handler, ex) -> null,
        new MockHttpServletRequest(),
        response,
        exception,
        status);

    final String type = exception.getClass().getSimpleName();
    assertEquals(status.value(), response.getStatus(), type + " の status");
    assertTrue(response.isCommitted(), type + " は sendError で commit すること");
    assertEquals("", response.getContentAsString(), type + " の本文は /error が書くこと");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("securityExceptions")
  @DisplayName("resolver が扱った例外では sendError しない")
  void doesNotSendErrorWhenResolverHandles(
      final String name, final Exception exception, final HttpStatus status) throws IOException {
    final MockHttpServletResponse response = new MockHttpServletResponse();

    SecurityConfig.resolveOrSendError(
        (request, res, handler, ex) -> new ModelAndView(),
        new MockHttpServletRequest(),
        response,
        exception,
        status);

    final String type = exception.getClass().getSimpleName();
    assertEquals(200, response.getStatus(), type + " の status は resolver に任せること");
    assertFalse(response.isCommitted(), type + " で sendError しないこと");
  }

  /** 認証と認可の例外を、表示名と期待する status の組で返す。 */
  private static Stream<Arguments> securityExceptions() {
    return Stream.of(
        Arguments.of(
            "401", new InsufficientAuthenticationException("test"), HttpStatus.UNAUTHORIZED),
        Arguments.of("403", new AccessDeniedException("test"), HttpStatus.FORBIDDEN));
  }
}
