package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * {@link TenantSecurityCustomizer} が足す filter が、実際の Security filter chain の中で、働くのに要る位置にあることを検証する。
 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class TenantSecurityCustomizerTest {

  /**
   * 前から順に並ぶ必要がある filter。MDC の利用者の ID は、セッションの認証を読んだ後、CSRF と認可の拒否より前に入れる。所属の引き直しは、拒否を Problem
   * Details に変える filter の後、認可の前に行う。
   */
  private static final List<Class<? extends Filter>> ORDER =
      List.of(
          SecurityContextHolderFilter.class,
          UserIdMdcFilter.class,
          CsrfFilter.class,
          ExceptionTranslationFilter.class,
          TenantSelectionFilter.class,
          AuthorizationFilter.class);

  /** 実際の Security filter chain。 */
  @Autowired private FilterChainProxy springSecurityFilterChain;

  @Test
  @DisplayName("利用者の ID を MDC に入れる filter を持つ chain は、決めた順に filter を持つ")
  void filtersAreOrderedWhereTheyWork() {
    final List<List<Class<? extends Filter>>> chains =
        springSecurityFilterChain.getFilterChains().stream()
            .filter(
                chain -> chain.getFilters().stream().anyMatch(UserIdMdcFilter.class::isInstance))
            .map(chain ->
                chain.getFilters().stream()
                    .<Class<? extends Filter>>map(Filter::getClass)
                    .filter(ORDER::contains)
                    .toList())
            .toList();

    assertThat(chains).as("UserIdMdcFilter を持つ chain").isNotEmpty().allMatch(ORDER::equals);
  }
}
