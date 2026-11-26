package com.example.demo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.accept.ApiVersionParser;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code /api/v{version}/...} の {@code v{version}} の区間を API のバージョンとして読む。
 *
 * <p>対応するバージョンは、Controller の {@code @RequestMapping(version = ...)} に書いた値から決まる。対応しないバージョンは 400
 * になる。バージョンのない {@code /api/...} は、どの mapping にも一致せず 404 になる。{@code /api/} の外のパスは対象にしない。
 */
@Configuration(proxyBeanMethods = false)
public class WebApiVersionConfig implements WebMvcConfigurer {

  /** パスの先頭から数えた、バージョンを置く区間の位置（{@code /api/v{version}} の {@code v{version}}）。 */
  private static final int VERSION_SEGMENT_INDEX = 1;

  /** バージョンの書き方。mapping の {@code version = "1"} と、パスの区間 {@code v1} の両方を読む。 */
  private static final Pattern VERSION = Pattern.compile("^v?([1-9][0-9]{0,8})$");

  /** 正の整数のメジャーバージョンだけを読む parser。 */
  private static final ApiVersionParser<Integer> VERSION_PARSER = WebApiVersionConfig::parseVersion;

  @Override
  public void configureApiVersioning(final ApiVersionConfigurer configurer) {
    // /error や /oauth2/** にはバージョンを求めないので、バージョンを必須にしない。
    configurer
        .usePathSegment(VERSION_SEGMENT_INDEX, WebApiVersionConfig::isApiPath)
        .setVersionParser(VERSION_PARSER)
        .setVersionRequired(false);
  }

  /**
   * 先頭の区間が {@code api} のパスかを返す。
   *
   * <p>MVC と Spring Security が使うデコード後の区間で見る。生の {@code path.value()} で見ると、{@code /%61pi/...}
   * がバージョンの検査を逃れ、コンテキストパスがあるときは全リクエストが逃れる。
   */
  private static boolean isApiPath(final RequestPath path) {
    return path.pathWithinApplication().elements().stream()
        .filter(PathContainer.PathSegment.class::isInstance)
        .map(PathContainer.PathSegment.class::cast)
        .findFirst()
        .filter(segment -> "api".equals(segment.valueToMatch()))
        .isPresent();
  }

  /**
   * 正の整数のメジャーバージョンだけを受ける。
   *
   * <p>Spring 既定の {@code SemanticApiVersionParser} は、先頭の数字以外を読み飛ばすので、{@code vX1}、{@code v01}、{@code
   * v1.0} も v1 になる。
   */
  private static Integer parseVersion(final String version) {
    final Matcher matcher = VERSION.matcher(version);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid API version format");
    }
    return Integer.valueOf(matcher.group(1));
  }
}
