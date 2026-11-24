import type { ComponentProps } from "react";

type EmailInputProps = Omit<ComponentProps<"input">, "type" | "onChange" | "value"> & {
  value: string;
  onValueChange: (value: string) => void;
};

/**
 * メールアドレスの入力欄。前後の空白を除いた値を渡す。
 * ブラウザの type="email" は ASCII の空白しか除かないので、IME で入る全角の空白もここで除く。
 * Orval が生成する Zod の schema は値を変えずに検査するので、空白は検査の前にここで除く（docs/mail/email-address.md）。
 */
export function EmailInput({ onValueChange, ...props }: EmailInputProps) {
  return (
    <input
      {...props}
      type="email"
      onChange={(event) => {
        onValueChange(event.target.value.trim());
      }}
    />
  );
}
