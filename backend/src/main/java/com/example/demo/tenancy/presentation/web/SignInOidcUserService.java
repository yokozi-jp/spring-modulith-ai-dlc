package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.tenancy.application.SignInUserCommand;
import com.example.demo.tenancy.application.SignInUserCommandHandler;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * IdP のログインの後に、ID トークンの {@code (iss, sub)} からアプリの利用者を引き、なければ作る。
 *
 * <p>返す {@link OidcUser} は {@link AppOidcUser} で、IdP のものと同じ ID トークンと権限を持ち、名前と {@link
 * com.example.demo.shared.security.AppUserPrincipal#userId()} がアプリの利用者の ID になる。共通カラムの {@code *_by}
 * は、{@code userId()} を登録する（{@code docs/database/postgresql-common-columns.md}）。
 */
@Component
class SignInOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  /** ログインの失敗の種類。 */
  private static final String INVALID_ID_TOKEN = "invalid_id_token";

  /** {@code ExternalIdentity} と同じ上限。{@code m_user_identity} の列が {@code varchar(255)} である。 */
  private static final int MAX_CLAIM_LENGTH = 255;

  /** IdP の利用者を読む標準の実装。 */
  private final OidcUserService delegate;

  /** IdP の識別に対応づけたアプリの利用者を引くか作る。 */
  private final SignInUserCommandHandler signInUser;

  /** 標準の実装で IdP の利用者を読む。 */
  /* package */ @Autowired
  SignInOidcUserService(final SignInUserCommandHandler signInUser) {
    this(withoutUserInfo(), signInUser);
  }

  /**
   * UserInfo を読まない標準の実装。使うのは ID トークンの {@code iss} と {@code sub} だけで、UserInfo の HTTP
   * クライアントはタイムアウトを持たないため、呼び出しごとなくす。
   */
  private static OidcUserService withoutUserInfo() {
    final OidcUserService service = new OidcUserService();
    service.setRetrieveUserInfo(request -> false);
    return service;
  }

  /** IdP の利用者を読む実装を差し替える。テストが使う。 */
  /* package */ SignInOidcUserService(
      final OidcUserService delegate, final SignInUserCommandHandler signInUser) {
    this.delegate = delegate;
    this.signInUser = signInUser;
  }

  @Override
  public OidcUser loadUser(final OidcUserRequest userRequest) {
    final OidcUser idpUser = delegate.loadUser(userRequest);
    // URL へ変換せず、claim の文字列をそのまま識別にする。
    final @Nullable String issuer = idpUser.getIdToken().getClaimAsString(IdTokenClaimNames.ISS);
    final @Nullable String subject = idpUser.getSubject();
    if (issuer == null || subject == null || !isValidClaim(issuer) || !isValidClaim(subject)) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(
              INVALID_ID_TOKEN, "the ID token has no iss or sub, or one is too long", null));
    }
    return new AppOidcUser(idpUser, signInWithoutPreviousSession(issuer, subject));
  }

  private static boolean isValidClaim(final String value) {
    return !value.isBlank() && value.length() <= MAX_CLAIM_LENGTH;
  }

  /**
   * ログインのコールバックでは、まだ前のセッションの認証が残っている。共通カラムの {@code *_by} にその利用者を登録しないよう、認証を外して利用者を作り、処理の名前を登録させる。
   */
  private String signInWithoutPreviousSession(final String issuer, final String subject) {
    final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    final Supplier<SecurityContext> previous = holder.getDeferredContext();
    holder.clearContext();
    try {
      return signIn(new SignInUserCommand(issuer, subject));
    } finally {
      holder.setDeferredContext(previous);
    }
  }

  /** 同じ識別での初めてのログインが同時に 2 つあり、相手が先に対応づけたときは、呼び直して相手の利用者を引く。 */
  private String signIn(final SignInUserCommand command) {
    try {
      return signInUser.handle(command).userId();
    } catch (ConflictException e) {
      if (e.kind() != ConflictException.Kind.UNIQUE) {
        throw e;
      }
      return signInUser.handle(command).userId();
    }
  }
}
