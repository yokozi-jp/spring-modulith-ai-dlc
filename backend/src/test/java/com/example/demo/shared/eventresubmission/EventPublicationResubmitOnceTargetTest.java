package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 手での 1 回の再投入の対象の文字列を、{@link Target} に読む規則。 */
class EventPublicationResubmitOnceTargetTest {

  @Test
  @DisplayName("all、policy:<名前>、event-type:<クラス> を型に読む")
  void parsesEachForm() {
    assertThat(Target.parse("all")).isEqualTo(new Target.All());
    assertThat(Target.parse("policy:payment")).isEqualTo(new Target.Policy("payment"));
    assertThat(Target.parse("event-type:java.lang.String"))
        .isEqualTo(new Target.EventType("java.lang.String"));
  }

  @ParameterizedTest(name = "対象 ''{0}'' は読めない")
  @ValueSource(strings = {"", "false", "OrderConfirmed", "policy:", "event-type:", "ALL"})
  @DisplayName("空白以外で対象として読めない値は、黙って何もしないのではなく失敗する")
  void rejectsUnreadableValues(final String raw) {
    assertThatThrownBy(() -> Target.parse(raw)).isInstanceOf(IllegalArgumentException.class);
  }
}
