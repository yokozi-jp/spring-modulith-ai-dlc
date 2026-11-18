package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.tenancy.application.SignInUserCommand;
import com.example.demo.tenancy.application.SignInUserCommandHandler;
import com.example.demo.tenancy.application.SignInUserResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** ログインの後に、アプリの利用者の ID を OidcUser の名前にし、同時の初回ログインでは呼び直すことを検証する。 */
// Mockito と AssertJ を static import で読みやすくする。
@SuppressWarnings("PMD.TooManyStaticImports")
class SignInOidcUserServiceTest {

  /** IdP の発行者。 */
  private static final String ISSUER = "https://idp.example.test/realms/demo";

  /** IdP の subject。 */
  private static final String SUBJECT = "idp-sub-1";

  /** アプリの利用者の ID。 */
  private static final String USER_ID = "6f1d3c5e-0b1a-4c6e-9d2f-3a4b5c6d7e8f";

  /** 標準の実装の代わりに、IdP の利用者を返す。 */
  private final OidcUserService delegate = mock(OidcUserService.class);

  /** 利用者を引くか作るユースケース。 */
  private final SignInUserCommandHandler handler = mock(SignInUserCommandHandler.class);

  /** テスト対象。 */
  private final SignInOidcUserService service = new SignInOidcUserService(delegate, handler);

  @Test
  @DisplayName("名前をアプリの利用者の ID にし、ID トークンと権限と IdP の subject は変えない")
  void namesTheUserByAppUserId() {
    final DefaultOidcUser idpUser = idpUser(Map.of());
    when(delegate.loadUser(any())).thenReturn(idpUser);
    when(handler.handle(new SignInUserCommand(ISSUER, SUBJECT)))
        .thenReturn(new SignInUserResult(USER_ID));

    final OidcUser user = service.loadUser(mock(OidcUserRequest.class));

    assertThat(user.getName()).as("OidcUser の名前").isEqualTo(USER_ID);
    assertThat(user.getSubject()).as("IdP の subject").isEqualTo(SUBJECT);
    assertThat(user.getIdToken()).isSameAs(idpUser.getIdToken());
    assertThat(List.copyOf(user.getAuthorities())).isEqualTo(List.copyOf(idpUser.getAuthorities()));
  }

  @Test
  @DisplayName("セッションの Redis に保存するため、直列化して読み戻しても、同じ名前と同じ ID トークンの利用者になる")
  void survivesSessionSerialization() {
    when(delegate.loadUser(any())).thenReturn(idpUser(Map.of()));
    when(handler.handle(any())).thenReturn(new SignInUserResult(USER_ID));
    final OidcUser user = service.loadUser(mock(OidcUserRequest.class));

    final byte[] bytes = Objects.requireNonNull(new SerializingConverter().convert(user));
    final Object restored = new DeserializingConverter().convert(bytes);

    assertThat(restored).isEqualTo(user);
    assertThat(((OidcUser) Objects.requireNonNull(restored)).getName()).isEqualTo(USER_ID);
  }

  @Test
  @DisplayName("IdP が同じ名前の claim を送ってきても、アプリが引いた ID で上書きする")
  void overridesAClaimSentByTheIdp() {
    when(delegate.loadUser(any())).thenReturn(idpUser(Map.of("app_user_id", "spoofed")));
    when(handler.handle(any())).thenReturn(new SignInUserResult(USER_ID));

    assertThat(service.loadUser(mock(OidcUserRequest.class)).getName()).isEqualTo(USER_ID);
  }

  @Test
  @DisplayName("初回ログインが同時に 2 つあり UNIQUE で負けたら、呼び直して相手の利用者を引く")
  void retriesOnceAfterUniqueConflict() {
    when(delegate.loadUser(any())).thenReturn(idpUser(Map.of()));
    when(handler.handle(any()))
        .thenThrow(
            new ConflictException(ConflictException.Kind.UNIQUE, "unique key already exists"))
        .thenReturn(new SignInUserResult(USER_ID));

    assertThat(service.loadUser(mock(OidcUserRequest.class)).getName()).isEqualTo(USER_ID);
    verify(handler, times(2)).handle(new SignInUserCommand(ISSUER, SUBJECT));
  }

  @Test
  @DisplayName("UNIQUE 以外の競合は呼び直さずに投げる")
  void doesNotRetryOtherConflicts() {
    when(delegate.loadUser(any())).thenReturn(idpUser(Map.of()));
    when(handler.handle(any()))
        .thenThrow(new ConflictException(ConflictException.Kind.LOCK, "row is locked"));

    assertThatThrownBy(() -> service.loadUser(mock(OidcUserRequest.class)))
        .isInstanceOf(ConflictException.class);
    verify(handler, times(1)).handle(any());
  }

  @Test
  @DisplayName("ID トークンに iss がなければ、利用者を引かずにログインを失敗させる")
  void rejectsIdTokenWithoutIssuer() {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject(SUBJECT)
            .issuedAt(Instant.parse("2026-10-01T00:00:00Z"))
            .expiresAt(Instant.parse("2026-10-01T00:05:00Z"))
            .build();
    when(delegate.loadUser(any()))
        .thenReturn(new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken));

    assertThatThrownBy(() -> service.loadUser(mock(OidcUserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class);
    verify(handler, times(0)).handle(any());
  }

  private static DefaultOidcUser idpUser(final Map<String, Object> extraClaims) {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .issuer(ISSUER)
            .subject(SUBJECT)
            .claims(claims -> claims.putAll(extraClaims))
            .issuedAt(Instant.parse("2026-10-01T00:00:00Z"))
            .expiresAt(Instant.parse("2026-10-01T00:05:00Z"))
            .build();
    return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
  }
}
