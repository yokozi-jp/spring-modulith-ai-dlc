package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/** API が対応する locale と Accept-Language の fallback を定義する。 */
@Configuration(proxyBeanMethods = false)
public class WebLocaleConfig {

  /** 日本語と英語を解決し、未指定または未対応の場合は日本語を返す。 */
  @Bean
  /* package */ LocaleResolver localeResolver() {
    final AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
    resolver.setSupportedLocales(LocaleSupport.supportedLocales());
    resolver.setDefaultLocale(LocaleSupport.defaultLocale());
    return resolver;
  }
}
