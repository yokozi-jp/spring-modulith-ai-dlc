import { describe, expect, it } from "vite-plus/test";
import { z } from "zod";

// oxlint-disable-next-line import/no-relative-parent-imports -- バックエンドと共有する入力が、フロントエンドの package の外の openapi/ にある。
import cases from "../../../../../openapi/mail-address-cases.json";

// バックエンドの MailAddressTest と同じ入力を使う。Orval は format: email から z.email() を生成する。
const clientEmail = z.email();

describe("メールアドレスのクライアントの検査", () => {
  it("Zod の式が、バックエンドの MailAddress.PATTERN と同じ文字列である", () => {
    expect(z.regexes.email.source).toBe(cases.pattern);
  });

  it.each(cases.accept)("%s を通す", (email) => {
    expect(clientEmail.safeParse(email).success).toBeTruthy();
  });

  it.each(cases.reject)("%j を断る", (email) => {
    expect(clientEmail.safeParse(email).success).toBeFalsy();
  });
});
