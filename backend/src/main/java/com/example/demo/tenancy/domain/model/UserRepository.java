package com.example.demo.tenancy.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.Optional;

/** 利用者の集約を保存し、取り出す。 */
public interface UserRepository {

  /** IdP の識別に対応づけた利用者を返す。なければ空を返す。 */
  Optional<User> findByIdentity(ExternalIdentity identity);

  /**
   * 新しい利用者を、対応づけた IdP の識別と一緒に保存する。
   *
   * @throws ConflictException 同じ IdP の識別が別の利用者に対応づけてある場合
   */
  void add(User user);
}
