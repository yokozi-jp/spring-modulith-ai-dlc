package com.example.demo;

import io.micrometer.common.KeyValue;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientRequestObservationConvention;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * 送信の HTTP の observation に、クエリと fragment を入れない。
 *
 * <p>Spring の既定は {@code http.url} と {@code uri} に {@code ?api_key=...} のような部分をそのまま入れ、span と {@code
 * http.client.requests} の tag に載せる。Boot の {@code RestClient.Builder} が、この Bean を使う。
 */
@Configuration(proxyBeanMethods = false)
public class ClientObservationConfig {

  /** 最初の {@code ?} か {@code #} から末尾まで。 */
  private static final Pattern QUERY_OR_FRAGMENT = Pattern.compile("[?#].*");

  /** 送信の observation の convention。 */
  @Bean
  public ClientRequestObservationConvention queryFreeClientRequestObservationConvention() {
    return new DefaultClientRequestObservationConvention() {
      @Override
      protected KeyValue uri(final ClientRequestObservationContext context) {
        return withoutQuery(super.uri(context));
      }

      @Override
      protected KeyValue requestUri(final ClientRequestObservationContext context) {
        return withoutQuery(super.requestUri(context));
      }
    };
  }

  private static KeyValue withoutQuery(final KeyValue keyValue) {
    return KeyValue.of(
        keyValue.getKey(), QUERY_OR_FRAGMENT.matcher(keyValue.getValue()).replaceFirst(""));
  }
}
