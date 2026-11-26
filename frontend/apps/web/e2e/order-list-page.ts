import type { Locator, Page } from "@playwright/test";

import type { OrderStatus, OrderStatusLabel } from "./order-status";

// 注文の一覧の画面（/orders）。画面の操作と要素を持ち、何を確かめるかは spec が持つ。
export class OrderListPage {
  private readonly page: Page;

  public constructor(page: Page) {
    this.page = page;
  }

  public get heading(): Locator {
    return this.page.getByRole("heading", { level: 1, name: "注文" });
  }

  public get createLink(): Locator {
    return this.page.getByRole("link", { name: "注文を作成する" });
  }

  // 注文の表の行。見出しの行を含む。
  public get rows(): Locator {
    return this.page.getByRole("table").getByRole("row");
  }

  public statusLink(label: OrderStatusLabel | "すべて"): Locator {
    return this.page
      .getByRole("navigation", { name: "状態で絞り込む" })
      .getByRole("link", { name: label });
  }

  public detailLink(code: string): Locator {
    return this.page.getByRole("link", { name: `${code} の詳細` });
  }

  public codeCell(code: string): Locator {
    return this.page.getByRole("cell", { name: code, exact: true });
  }

  public async goto(status?: OrderStatus): Promise<void> {
    await this.page.goto(status === undefined ? "/orders" : `/orders?status=${status}`);
  }

  public async openDetail(code: string, status?: OrderStatus): Promise<void> {
    await this.goto(status);
    await this.detailLink(code).click();
  }
}
