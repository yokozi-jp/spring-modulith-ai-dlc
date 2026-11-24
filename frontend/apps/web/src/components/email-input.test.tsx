/* @vitest-environment jsdom */

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vite-plus/test";

import { EmailInput } from "@/components/email-input";

describe("メールアドレスの入力欄", () => {
  it("前後の空白を除いた値を渡す", async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn<(value: string) => void>();
    render(<EmailInput aria-label="mail" value="" onValueChange={onValueChange} />);

    await user.click(screen.getByLabelText("mail"));
    // type="email" の値の正規化は ASCII の空白だけを除くので、全角の空白で確かめる。
    await user.paste("\u3000user@example.com\u3000");

    expect(onValueChange).toHaveBeenLastCalledWith("user@example.com");
  });
});
