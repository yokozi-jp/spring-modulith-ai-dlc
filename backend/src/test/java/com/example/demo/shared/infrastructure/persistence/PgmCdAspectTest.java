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
    final PlaceOrderCommandHandler handler =
        proxy(new PlaceOrderCommandHandler(proxy(new StockReservedListener())));

    assertThat(handler.handle().getFirst()).isEqualTo("shared.PlaceOrder");
  }

  @Test
  @DisplayName("Listener の public メソッドの中では、Listener を除いた単純名の pgm_cd が束縛される")
  void listenerBindsPgmCd() {
    assertThat(proxy(new StockReservedListener()).on()).isEqualTo("shared.StockReserved");
  }

  @Test
  @DisplayName("CommandHandler の中で同期の Listener を呼ぶと、その間は内側の pgm_cd になり、戻ると外側に戻る")
  void nestedBindingOverridesOuter() {
    final PlaceOrderCommandHandler handler =
        proxy(new PlaceOrderCommandHandler(proxy(new StockReservedListener())));

    assertThat(handler.handle())
        .as("呼ぶ前、Listener の中、戻った後の pgm_cd")
        .containsExactly("shared.PlaceOrder", "shared.StockReserved", "shared.PlaceOrder");
  }

  @Test
  @DisplayName("CommandHandler の handle 以外のメソッドと、役割名でないクラスでは pgm_cd を束縛しない")
  void otherMethodsAreNotBound() {
    final PlaceOrderCommandHandler handler =
        proxy(new PlaceOrderCommandHandler(new StockReservedListener()));

    assertThatThrownBy(handler::describe)
        .as("CommandHandler の handle 以外")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
    assertThatThrownBy(proxy(new PlaceOrderService())::handle)
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

  /** pointcut の対象になる CommandHandler。束縛された pgm_cd を返す。 */
  // CGLIB のプロキシを作れるよう、final にしない。pointcut の対象にするため、メソッドを public にする。
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  /* package */ static class PlaceOrderCommandHandler {

    /** handle の中で同期で呼ぶ Listener。 */
    private final StockReservedListener listener;

    /* package */ PlaceOrderCommandHandler(final StockReservedListener listener) {
      this.listener = listener;
    }

    /** 呼ぶ前、Listener の中、戻った後の pgm_cd を返す。 */
    public List<String> handle() {
      final String before = PgmCdAspect.current();
      final String inner = listener.on();
      return List.of(before, inner, PgmCdAspect.current());
    }

    /** pointcut の対象にならないメソッド。 */
    public String describe() {
      return PgmCdAspect.current();
    }
  }

  /** pointcut の対象になる Listener。 */
  @SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
  /* package */ static class StockReservedListener {

    /** 束縛された pgm_cd を返す。 */
    public String on() {
      return PgmCdAspect.current();
    }
  }

  /** 役割名でないため、pointcut の対象にならないクラス。 */
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  /* package */ static class PlaceOrderService {

    /** 束縛された pgm_cd を返す。 */
    public String handle() {
      return PgmCdAspect.current();
    }
  }
}
