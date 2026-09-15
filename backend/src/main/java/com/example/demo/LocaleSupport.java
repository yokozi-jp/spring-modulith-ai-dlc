package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** API と MVC が共有する対応 locale と既定値を提供する。 */
public final class LocaleSupport {

  /** API が対応する language-only locale。 */
  private static final List<Locale> SUPPORTED = List.of(Locale.JAPANESE, Locale.ENGLISH);

  private LocaleSupport() {}

  /** Accept-Language がない場合と未対応の場合の locale を返す。 */
  public static Locale defaultLocale() {
    return Locale.JAPANESE;
  }

  /** Spring MVC の AcceptHeaderLocaleResolver に設定する対応 locale を返す。 */
  public static List<Locale> supportedLocales() {
    return SUPPORTED;
  }

  /** リクエストの言語優先順から、最初に対応する language-only locale を返す。 */
  public static Locale resolve(final HttpServletRequest request) {
    final String acceptLanguage = request.getHeader("Accept-Language");
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return defaultLocale();
    }
    final Enumeration<Locale> requestedLocales = request.getLocales();
    while (requestedLocales.hasMoreElements()) {
      final String requestedLanguage = requestedLocales.nextElement().getLanguage();
      for (final Locale supportedLocale : SUPPORTED) {
        if (supportedLocale.getLanguage().equals(requestedLanguage)) {
          return supportedLocale;
        }
      }
    }
    return defaultLocale();
  }
}
