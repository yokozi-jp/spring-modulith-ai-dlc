package com.example.demo.tenancy.application;

import com.example.demo.shared.security.NoRoleRequired;
import com.example.demo.tenancy.domain.model.ExternalIdentity;
import com.example.demo.tenancy.domain.model.User;
import com.example.demo.tenancy.domain.model.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** IdP の識別に対応づけた利用者を返す。初めてのログインなら、利用者を作って対応づける。 */
@Service
public class SignInUserCommandHandler {

  /** 利用者を保存する Repository。 */
  private final UserRepository userRepository;

  /** 依存を受け取る。 */
  public SignInUserCommandHandler(final UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * 利用者を引き、なければ作る。
   *
   * @throws com.example.demo.shared.concurrency.ConflictException 同じ識別での初めてのログインが同時に 2
   *     つあり、相手が先に対応づけた場合。呼び直すと相手の利用者を引く
   */
  @NoRoleRequired(reason = "OIDC のログインの途中で呼ぶ。役割とテナントはまだない")
  @Transactional
  public SignInUserResult handle(final SignInUserCommand command) {
    final ExternalIdentity identity = new ExternalIdentity(command.issuer(), command.subject());
    final Optional<User> existing = userRepository.findByIdentity(identity);
    if (existing.isPresent()) {
      return new SignInUserResult(existing.get().id().value().toString());
    }
    final User registered = User.register(identity);
    userRepository.add(registered);
    return new SignInUserResult(registered.id().value().toString());
  }
}
