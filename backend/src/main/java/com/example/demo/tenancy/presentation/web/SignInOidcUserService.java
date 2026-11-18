package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.tenancy.application.SignInUserCommand;
import com.example.demo.tenancy.application.SignInUserCommandHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>返す {@link OidcUser} は {@link AppOidcUser} で、IdP のものと同じ ID トークンと権限を持ち、{@link
 * OidcUser#getName()} だけがアプリの利用者の ID になる。共通カラムの {@code *_by} は、この名前を登録する（{@code
 * docs/database/postgresql-common-columns.md}）。
 */
@Component
class SignInOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  /** ログインの失敗の種類。 */
  private static final String INVALID_ID_TOKEN = "invalid_id_token";

  /** IdP の利用者を読む標準の実装。 */
  private final OidcUserService delegate;

  /** IdP の識別に対応づけたアプリの利用者を引くか作る。 */
  private final SignInUserCommandHandler signInUser;

  /** 標準の実装で IdP の利用者を読む。 */
  /* package */ @Autowired
  SignInOidcUserService(final SignInUserCommandHandler signInUser) {
    this(new OidcUserService(), signInUser);
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
    if (issuer == null || subject == null) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error(INVALID_ID_TOKEN, "the ID token has no iss or sub", null));
    }
    return new AppOidcUser(idpUser, signIn(new SignInUserCommand(issuer, subject)));
  }

  /**
   * 同じ識別での初めてのログインが同時に 2 つあり、相手が先に対応づけたときは、呼び直して相手の利用者を引く。
   *
   * <p>ponytail: ログインの途中で呼ぶため、別の利用者のセッションが残ったまま新しい利用者が初めてログインすると、新しい利用者の行の {@code created_by}
   * にその別の利用者の ID が入る。共通カラムは業務ロジックで参照しない監査用なので許す。直すなら、この書き込みの {@code *_by} を処理の名前に固定する。
   */
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
