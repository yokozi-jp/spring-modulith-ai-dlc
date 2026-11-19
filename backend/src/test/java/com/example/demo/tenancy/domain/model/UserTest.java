package com.example.demo.tenancy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 利用者の採番と、IdP の識別を持つ不変条件と、識別が列に入らない値を作れないことを検証する。 */
class UserTest {

  /** 発行者の例。 */
  private static final String ISSUER = "https://idp.example.test/realms/demo";

  /** 対応づける IdP の識別。 */
  private static final ExternalIdentity IDENTITY = new ExternalIdentity(ISSUER, "sub-1");

  @Test
  @DisplayName("登録した利用者は、UUID v4 の ID と、対応づけた識別と、ロック番号 1 を持つ")
  void registeredUserHasV4IdAndIdentity() {
    final User user = User.register(IDENTITY);

    assertThat(user.id().value().version()).as("利用者 ID の UUID の版").isEqualTo(4);
    assertThat(user.identities()).containsExactly(IDENTITY);
    assertThat(user.lockNo()).isEqualTo(1L);
  }

  @Test
  @DisplayName("登録のたびに別の利用者 ID を採番する")
  void registrationIssuesDistinctIds() {
    assertThat(User.register(IDENTITY).id()).isNotEqualTo(User.register(IDENTITY).id());
  }

  @Test
  @DisplayName("IdP の識別のない利用者は復元できない")
  void rejectsUserWithoutIdentity() {
    final UserId id = new UserId(UUID.randomUUID());

    assertThatThrownBy(() -> User.restore(id, List.of(), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId=" + id.value());
  }

  @Test
  @DisplayName("識別の発行者と subject が 1 文字以上 255 文字以下なら作れる")
  void acceptsIdentityWithinTheColumnLength() {
    assertThat(new ExternalIdentity(ISSUER, "s".repeat(255)).subject()).hasSize(255);
  }

  @Test
  @DisplayName("空白だけの発行者は、IllegalArgumentException になる")
  void rejectsBlankIssuer() {
    assertThatThrownBy(() -> new ExternalIdentity(" ", "sub-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer");
  }

  @Test
  @DisplayName("256 文字の subject は、IllegalArgumentException になる")
  void rejectsTooLongSubject() {
    final String subject = "s".repeat(256);

    assertThatThrownBy(() -> new ExternalIdentity(ISSUER, subject))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subject")
        .hasMessageContaining("length=256");
  }
}
