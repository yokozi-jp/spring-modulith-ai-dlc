import type { Locator, Page } from "@playwright/test";

import { logIn } from "./environment";
import type { Product } from "./fixtures";
import { expect, test, uniqueCode } from "./fixtures";
import type { OrderCreatePage } from "./order-create-page";
import type { OrderDetailPage } from "./order-detail-page";
import type { OrderListPage } from "./order-list-page";
import { expectLoggedOutPage, homeHeading, logoutButton } from "./pages";

// 画面ごとに、配色の組ごとに axe を流す（ADR-142）。Keycloak のログインの画面はこの repo の画面ではないので含めない。
// 配色や幅の組を足すときは、variants に 1 行足す（例：{ name: "ダーク 320px", use: { colorScheme: "dark", viewport: { width: 320, height: 800 } } }）。
const variants = [
  { name: "ライト", use: { colorScheme: "light" } },
  { name: "ダーク", use: { colorScheme: "dark" } },
] as const;

interface Screens {
  page: Page;
  orderCreatePage: OrderCreatePage;
  orderListPage: OrderListPage;
  orderDetailPage: OrderDetailPage;
  productFactory: () => Promise<Product>;
}

// 下書きの注文を画面で作り、詳細の画面を開いたまま、客先注文番号を返す（ADR-130）。
async function createDraft({ orderCreatePage, productFactory }: Screens): Promise<string> {
  const code = uniqueCode("E2E-A11Y");
  const product = await productFactory();
  await orderCreatePage.create({ code, productName: product.productName, quantity: 1 });
  return code;
}

function notFoundHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "ページが見つかりません" });
}

// open は画面を開き、開き終えるまで待つ。axe はその後に流す。
const screens: { name: string; open: (screens: Screens) => Promise<void> }[] = [
  {
    name: "トップ",
    open: async ({ page }) => {
      await page.goto("/");
      await expect(homeHeading(page)).toBeVisible();
    },
  },
  {
    // 空の一覧ではなく表を検査するため、自分の注文を 1 つ作ってから開く。
    name: "注文の一覧",
    open: async (fixtures) => {
      const code = await createDraft(fixtures);
      await fixtures.orderListPage.goto();
      await expect(fixtures.orderListPage.codeCell(code)).toBeVisible();
    },
  },
  {
    name: "注文の作成",
    open: async ({ orderCreatePage }) => {
      await orderCreatePage.goto();
      await expect(orderCreatePage.heading).toBeVisible();
    },
  },
  {
    name: "注文の作成の検証エラー",
    open: async ({ orderCreatePage }) => {
      await orderCreatePage.goto();
      await orderCreatePage.submitButton.click();
      // 文言を欄に結び付ける方法そのものを axe が検査するので、待つのは誤りの印だけにする。
      await expect(orderCreatePage.customerOrderCodeInput).toHaveAttribute("aria-invalid", "true");
    },
  },
  {
    name: "下書きの注文の詳細",
    open: async (fixtures) => {
      await createDraft(fixtures);
      await expect(fixtures.orderDetailPage.cancelButton).toBeVisible();
    },
  },
  {
    name: "確定済みの注文の詳細",
    open: async (fixtures) => {
      const { orderDetailPage } = fixtures;
      await createDraft(fixtures);
      await orderDetailPage.confirmButton.click();
      // 決済の欄は自分で読み直す間に文言を変えるので、決済済みになってから検査する。
      await expect(orderDetailPage.paymentStatus).toHaveText("決済済み", {
        timeout: orderDetailPage.paymentTextTimeoutMs,
      });
    },
  },
  {
    // test-user はどちらのテナントでも管理者でないので、管理者だけが使えることを示す画面になる。
    name: "メンバー",
    open: async ({ page }) => {
      await page.goto("/members");
      await expect(
        page.getByRole("heading", { level: 1, name: "管理者だけが使える画面です" }),
      ).toBeVisible();
    },
  },
  {
    name: "テナントの選択",
    open: async ({ page }) => {
      await page.goto("/tenants");
      await expect(
        page.getByRole("heading", { level: 1, name: "テナントを選んでください" }),
      ).toBeVisible();
    },
  },
  {
    name: "404",
    open: async ({ page }) => {
      await page.goto("/no-such-page");
      await expect(notFoundHeading(page)).toBeVisible();
    },
  },
];

for (const variant of variants) {
  test.describe(variant.name, () => {
    test.use(variant.use);

    for (const screen of screens) {
      test(`${screen.name}に axe の違反がない`, async ({
        page,
        orderCreatePage,
        orderListPage,
        orderDetailPage,
        productFactory,
        expectNoA11yViolations,
      }) => {
        await screen.open({
          page,
          orderCreatePage,
          orderListPage,
          orderDetailPage,
          productFactory,
        });

        await expectNoA11yViolations(screen.name);
      });
    }

    test.describe("ログアウトした後", () => {
      // 共有の storageState のセッションを終わらせないよう、専用の context でログインする。
      test.use({ storageState: { cookies: [], origins: [] } });

      test("ログアウト後の画面と 404 に axe の違反がない", async ({
        page,
        expectNoA11yViolations,
      }) => {
        await logIn(page);
        await logoutButton(page).click();
        await expectLoggedOutPage(page);
        await expectNoA11yViolations("ログアウト後の画面");

        await page.goto("/no-such-page");
        await expect(notFoundHeading(page)).toBeVisible();
        await expectNoA11yViolations("ログアウトした後の 404");
      });
    });
  });
}
