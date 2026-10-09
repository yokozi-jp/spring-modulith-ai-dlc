import { createInstance } from "i18next";
import { describe, expect, it } from "vite-plus/test";

import ja from "@/i18n/locales/ja.json";

import { firstErrorMessage, productOptions } from "./form";
import { orderStatusLabel, salesStatusLabel } from "./order-status";

// @/i18n は document を使うため、Node のテストでは catalog だけを読んだ instance を作る。
const i18n = createInstance();
await i18n.init({ lng: "ja", resources: { ja: { translation: ja } } });
const { t } = i18n;

describe("order form helpers", () => {
  it("errors の最初の issue の message を返し、なければ undefined を返す", () => {
    expect(firstErrorMessage([{ message: "first" }, { message: "second" }])).toBe("first");
    expect(firstErrorMessage([])).toBeUndefined();
  });

  it("販売中の商品と、選択中の販売終了の商品だけを選択肢にする", () => {
    const product = { productCode: "P", unitPrice: 1 };
    const options = productOptions(
      t,
      [
        { ...product, productId: "a", productName: "A", salesStatus: "ON_SALE" },
        { ...product, productId: "b", productName: "B", salesStatus: "DISCONTINUED" },
        { ...product, productId: "c", productName: "C", salesStatus: "DISCONTINUED" },
      ],
      "c",
    );

    expect(options).toStrictEqual([
      { productId: "a", label: "A" },
      { productId: "c", label: "C（販売終了）" },
    ]);
  });

  it("対応にない状態はコード値を、値がなければ「（なし）」を返す", () => {
    expect([
      orderStatusLabel(t, "DRAFT"),
      orderStatusLabel(t, "PAID"),
      orderStatusLabel(t),
    ]).toStrictEqual(["下書き", "PAID", "（なし）"]);
    expect([salesStatusLabel(t, "UNKNOWN"), salesStatusLabel(t)]).toStrictEqual([
      "UNKNOWN",
      "（なし）",
    ]);
  });
});
