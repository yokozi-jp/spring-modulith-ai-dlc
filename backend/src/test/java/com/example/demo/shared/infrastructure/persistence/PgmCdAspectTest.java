package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/** CommandHandler と Listener の呼び出しの間だけ、pgm_cd が束縛されることを検証する。 */
class PgmCdAspectTest {

  @Test
  @DisplayName("CommandHandler の handle の中では、モジュール名と CommandHandler を除いた単純名の pgm_cd が束縛される")
  void commandHandlerBindsPgmCd() {
    assertThat(proxy(new ChargeOrderCommandHandler()).handle()).isEqualTo("shared.ChargeOrder");
  }

  @Test
  @DisplayName("Listener の public メソッドの中では、Listener を除いた単純名の pgm_cd が束縛される")
  void listenerBindsPgmCd() {
    final OrderConfirmedListener listener =
        proxy(new OrderConfirmedListener(proxy(new ChargeOrderCommandHandler())));

    assertThat(listener.on().getFirst()).isEqualTo("shared.OrderConfirmed");
  }

  @Test
  @DisplayName("Listener の中で CommandHandler を呼ぶと、その間は内側の pgm_cd になり、戻ると外側に戻る")
  void nestedBindingOverridesOuter() {
    final OrderConfirmedListener listener =
        proxy(new OrderConfirmedListener(proxy(new ChargeOrderCommandHandler())));

    assertThat(listener.on())
        .as("呼ぶ前、CommandHandler の中、戻った後の pgm_cd")
        .containsExactly("shared.OrderConfirmed", "shared.ChargeOrder", "shared.OrderConfirmed");
  }

  @Test
  @DisplayName("CommandHandler の handle 以外のメソッドと、役割名でないクラスでは pgm_cd を束縛しない")
  void otherMethodsAreNotBound() {
    assertThatThrownBy(proxy(new ChargeOrderCommandHandler())::describe)
        .as("CommandHandler の handle 以外")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
    assertThatThrownBy(proxy(new ChargeOrderService())::handle)
        .as("役割名でないクラス")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
  }

  private static <T> T proxy(final T target) {
    final AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAspect(new PgmCdAspect());
    return factory.getProxy();
  }

  /** pointcut の対象になる CommandHandler。 */
  // CGLIB のプロキシを作れるよう、final にしない。pointcut の対象にするため、メソッドを public にする。
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  /* package */ static class ChargeOrderCommandHandler {

    /** 束縛された pgm_cd を返す。 */
    public String handle() {
      return PgmCdAspect.current();
    }

    /** pointcut の対象にならないメソッド。 */
    public String describe() {
      return PgmCdAspect.current();
    }
  }

  /** pointcut の対象になる Listener。ADR-050 のとおり、on から CommandHandler を一つ呼ぶ。 */
  @SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
  /* package */ static class OrderConfirmedListener {

    /** on の中で呼ぶ CommandHandler。 */
    private final ChargeOrderCommandHandler handler;

    /* package */ OrderConfirmedListener(final ChargeOrderCommandHandler handler) {
      this.handler = handler;
    }

    /** 呼ぶ前、CommandHandler の中、戻った後の pgm_cd を返す。 */
    public List<String> on() {
      final String before = PgmCdAspect.current();
      final String inner = handler.handle();
      return List.of(before, inner, PgmCdAspect.current());
    }
  }

  /** 役割名でないため、pointcut の対象にならないクラス。 */
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  /* package */ static class ChargeOrderService {

    /** 束縛された pgm_cd を返す。 */
    public String handle() {
      return PgmCdAspect.current();
    }
  }
}
