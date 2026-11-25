import type { Locator, Page } from "@playwright/test";

export type OrderStatusLabel = "下書き" | "確定済み" | "取消済み";

// 注文の一覧の画面（/orders）。画面の操作と要素を持ち、何を確かめるかは spec が持つ。
export class OrderListPage {
  private readonly page: Page;

  public constructor(page: Page) {
    this.page = page;
  }

  public async goto(): Promise<void> {
    await this.page.goto("/orders");
  }

  public async openDetail(code: string): Promise<void> {
    await this.goto();
    await this.page.getByRole("link", { name: `${code} の詳細` }).click();
  }

  public statusLink(label: OrderStatusLabel): Locator {
    return this.page
      .getByRole("navigation", { name: "状態で絞り込む" })
      .getByRole("link", { name: label });
  }

  public codeCell(code: string): Locator {
    return this.page.getByRole("cell", { name: code, exact: true });
  }
}
