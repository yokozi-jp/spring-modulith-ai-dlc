import { screen, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";

async function changeQuantity(user: UserEvent, value: string) {
  const quantity = await screen.findByLabelText("数量");
  await user.clear(quantity);
  await user.type(quantity, value);
}

/** 決済の欄の外の status（ProblemNotice の output）。 */
export function getNotice() {
  const payment = screen.queryByRole("region", { name: "決済" });
  const inPayment = payment === null ? [] : within(payment).queryAllByRole("status");
  const element = screen.getAllByRole("status").find((el) => !inPayment.includes(el));
  if (element === undefined) {
    throw new Error("ProblemNotice の status がない");
  }
  return element;
}

/** 数量を value にして明細の変更を送る。 */
export async function saveQuantity(user: UserEvent, value: string): Promise<void> {
  await changeQuantity(user, value);
  await user.click(screen.getByRole("button", { name: "明細を保存する" }));
}

/** 詳細の明細の表の最初の行（見出しの行の次）。 */
export function getFirstLineRow(): HTMLElement | undefined {
  const [table] = screen.getAllByRole("table");
  return table === undefined ? undefined : within(table).getAllByRole("row")[1];
}
