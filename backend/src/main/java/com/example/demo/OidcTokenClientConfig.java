package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * ログインのコールバックで、認可コードをトークンに替える HTTP クライアント。
 *
 * <p>Spring Security の既定は {@code RestClient.builder()} で作るため、{@code spring.http.clients.*}
 * のタイムアウトが入らず、IdP が応答しないと待ち続ける。Boot の {@link RestClient.Builder} から作り、{@code oauth2Login} が bean
 * として使う。converter とエラーの処理は、Spring Security の既定と同じものを入れる。
 */
@Configuration(proxyBeanMethods = false)
public class OidcTokenClientConfig {

  /** 認可コードをトークンに替えるクライアント。 */
  @Bean
  public RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenResponseClient(
      final RestClient.Builder restClientBuilder) {
    final OAuth2ErrorResponseErrorHandler errorHandler = new OAuth2ErrorResponseErrorHandler();
    final RestClientAuthorizationCodeTokenResponseClient client =
        new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(
        restClientBuilder
            .configureMessageConverters(converters -> {
              converters.addCustomConverter(new FormHttpMessageConverter());
              converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
            })
            .defaultStatusHandler(
                HttpStatusCode::isError,
                (request, response) ->
                    errorHandler.handleError(request.getURI(), request.getMethod(), response))
            .build());
    return client;
  }
}
