import { describe, expect, it } from "vite-plus/test";

import { draftOrderHeaderIdempotencyKeyRegExp } from "./generated/zod/ordering/ordering.zod";
import { idempotencyKeyHeaders } from "./idempotency-key";

describe("idempotencyKeyHeaders", () => {
  it("呼ぶたびに、契約の pattern（引用符で囲んだ UUID）に合う新しいキーを作る", () => {
    const first = idempotencyKeyHeaders()["Idempotency-Key"];
    const second = idempotencyKeyHeaders()["Idempotency-Key"];

    expect(first).toMatch(draftOrderHeaderIdempotencyKeyRegExp);
    expect(second).toMatch(draftOrderHeaderIdempotencyKeyRegExp);
    expect(second).not.toBe(first);
  });
});
