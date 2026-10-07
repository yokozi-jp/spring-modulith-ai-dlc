import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";

import { useListPayments } from "@/api/generated/endpoints/payment/payment";
import type { PaymentSummaryResponse } from "@/api/generated/models";

function paymentMessage(
  t: TFunction,
  state: {
    isPending: boolean;
    isError: boolean;
    payment: PaymentSummaryResponse | undefined;
    orderStatus: string | undefined;
  },
): string {
  if (state.isPending) {
    return t("orders.payment.loading");
  }
  if (state.isError) {
    return t("orders.payment.loadFailed");
  }
  if (state.payment !== undefined) {
    return t("orders.payment.paid");
  }
  return state.orderStatus === "CONFIRMED"
    ? t("orders.payment.notYet")
    : t("orders.payment.notApplicable");
}

// 金額と時刻は live region の外に置き、読み上げは「決済済み」の文言の変化で伝える。
function paidDetails(t: TFunction, language: string, payment: PaymentSummaryResponse) {
  const missing = t("orders.missingValue");

  return (
    <dl className="grid max-w-md grid-cols-2 gap-x-4 gap-y-2">
      <dt className="text-muted-foreground">{t("orders.payment.paidAmount")}</dt>
      <dd>
        {payment.amount === undefined ? missing : t("orders.amount", { value: payment.amount })}
      </dd>
      <dt className="text-muted-foreground">{t("orders.payment.paidAt")}</dt>
      <dd>
        {payment.paidAt === undefined ? (
          missing
        ) : (
          // タイムゾーンは指定せず、ブラウザの既定で表示する（ADR-047）。
          <time dateTime={payment.paidAt}>
            {Temporal.Instant.from(payment.paidAt).toLocaleString(language, {
              dateStyle: "medium",
              timeStyle: "medium",
            })}
          </time>
        )}
      </dd>
    </dl>
  );
}

// 注文の詳細とは別に決済の参照を suspense なしで読み、失敗しても注文の表示と操作を残す（#122 の P-11）。
export function PaymentStatus({
  orderId,
  orderStatus,
}: {
  orderId: string;
  orderStatus: string | undefined;
}) {
  const { t, i18n } = useTranslation();
  // 再試行しない。mutation の後の全 query の無効化はこの query の再取得も待つため、
  // 5xx の再試行の待ちの間、確定や明細の変更が「送信しています」のまま残る。
  const { data, isPending, isError } = useListPayments({ orderId }, { query: { retry: false } });
  const payment = data?.data.items?.[0];

  return (
    <section aria-labelledby="payment-heading" className="flex flex-col gap-2">
      <h2 id="payment-heading" className="text-xl font-semibold">
        {t("orders.payment.heading")}
      </h2>
      {/* 最初の描画から置く live region。状態の文言だけを入れ、変わったら読み上げる。 */}
      <output className="block">
        {paymentMessage(t, { isPending, isError, payment, orderStatus })}
      </output>
      {payment === undefined ? undefined : paidDetails(t, i18n.language, payment)}
    </section>
  );
}
