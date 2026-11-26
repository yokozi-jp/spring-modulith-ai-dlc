package com.example.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code /api/v{version}/...} の {@code {version}} を API のバージョンとして読む。
 *
 * <p>対応するバージョンは、Controller の {@code @RequestMapping(version = ...)} に書いた値から決まる。対応しないバージョンは 400
 * になる。バージョンのない {@code /api/...} は、どの mapping にも一致せず 404 になる。{@code /api/} の外のパスは対象にしない。
 */
@Configuration(proxyBeanMethods = false)
public class WebApiVersionConfig implements WebMvcConfigurer {

  /** パスの先頭から数えた、バージョンを置く区間の位置（{@code /api/v{version}} の {@code v{version}}）。 */
  private static final int VERSION_SEGMENT_INDEX = 1;

  @Override
  public void configureApiVersioning(final ApiVersionConfigurer configurer) {
    // バージョンを読むのは /api/ のパスだけなので、ほかのパス（/error、/oauth2/**）にはバージョンを求めない。
    configurer
        .usePathSegment(VERSION_SEGMENT_INDEX, path -> path.value().startsWith("/api/"))
        .setVersionRequired(false);
  }
}
