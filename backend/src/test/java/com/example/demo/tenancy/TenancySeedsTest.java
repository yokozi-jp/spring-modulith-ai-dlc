package com.example.demo.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 開発用の代表データの所属が、ローカルの Keycloak の利用者を指すことを確かめる。 */
class TenancySeedsTest {

  /** ローカルの Keycloak が読み込む realm。テストは backend を作業ディレクトリにして走る。 */
  private static final Path REALM = Path.of("..", "docker", "keycloak", "realm.json");

  @Test
  @DisplayName("seed が所属させる subject は、ローカルの Keycloak の realm の test-user と test-admin の ID と同じ")
  void seededSubjectsMatchLocalRealmUsers() {
    final JsonNode users = JsonMapper.builder().build().readTree(REALM.toFile()).get("users");

    final List<String> pairs =
        users
            .valueStream()
            .map(user -> user.get("username").asString() + "=" + user.get("id").asString())
            .toList();

    assertThat(pairs)
        .contains(
            "test-user=" + TenancySeeds.TEST_USER_SUBJECT,
            "test-admin=" + TenancySeeds.TEST_ADMIN_SUBJECT);
  }
}
