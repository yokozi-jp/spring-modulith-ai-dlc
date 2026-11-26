import { logIn } from "./environment";
import type { App } from "./fixtures";
import { expect, test } from "./fixtures";
import {
  expectLoggedOutPage,
  homeHeading,
  loginFailedHeading,
  logoutButton,
  membersForbiddenHeading,
  notFoundHeading,
  sessionExpiredHeading,
  tenantsHeading,
} from "./pages";

// 画面ごとに、配色の組ごとに axe を流す（ADR-142）。Keycloak のログインの画面はこの repo の画面ではないので含めない。
// 配色や幅の組を足すときは、variants に 1 行足す。
const variants = [
  { name: "ライト", use: { colorScheme: "light" } },
  { name: "ダーク", use: { colorScheme: "dark" } },
] as const;

// open は画面を開き、開き終えるまで待つ。axe はその後に流す。
const screens: { name: string; open: (app: App) => Promise<void> }[] = [
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
    open: async ({ createOrder, orderListPage }) => {
      const { code } = await createOrder();
      await orderListPage.goto();
      await expect(orderListPage.codeCell(code)).toBeVisible();
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
    open: async ({ createOrder, orderDetailPage }) => {
      await createOrder();
      await expect(orderDetailPage.cancelButton).toBeVisible();
    },
  },
  {
    name: "確定済みの注文の詳細",
    open: async ({ createOrder, orderDetailPage }) => {
      await createOrder({ status: "確定済み" });
      // 決済の欄は自分で読み直す間に文言を変えるので、決済済みになってから検査する。
      await expect(orderDetailPage.paymentStatus).toHaveText("決済済み", {
        timeout: orderDetailPage.paymentTextTimeoutMs,
      });
    },
  },
  {
    name: "注文の詳細の 404",
    open: async ({ page }) => {
      await page.goto(`/orders/${crypto.randomUUID()}`);
      await expect(notFoundHeading(page)).toBeVisible();
      await expect(logoutButton(page)).toBeVisible();
    },
  },
  {
    // test-user はどちらのテナントでも管理者でないので、権限がないことを示す画面になる。
    name: "メンバー",
    open: async ({ page }) => {
      await page.goto("/members");
      await expect(membersForbiddenHeading(page)).toBeVisible();
    },
  },
  {
    name: "テナントの選択",
    open: async ({ page }) => {
      await page.goto("/tenants");
      await expect(tenantsHeading(page)).toBeVisible();
    },
  },
  {
    name: "404",
    open: async ({ page }) => {
      await page.goto("/no-such-page");
      await expect(notFoundHeading(page)).toBeVisible();
    },
  },
  {
    name: "ログインの失敗",
    open: async ({ page }) => {
      await page.goto("/login-failed");
      await expect(loginFailedHeading(page)).toBeVisible();
    },
  },
  {
    name: "ログインの有効期限切れ",
    open: async ({ page }) => {
      await page.goto("/session-expired");
      await expect(sessionExpiredHeading(page)).toBeVisible();
    },
  },
];

for (const variant of variants) {
  test.describe(variant.name, () => {
    test.use(variant.use);

    for (const screen of screens) {
      test(`${screen.name}に axe の違反がない`, async ({ app, expectNoA11yViolations }) => {
        await screen.open(app);

        await expectNoA11yViolations(screen.name);
      });
    }

    test.describe("ログアウトした後", () => {
      // 共有の storageState のセッションを終わらせないよう、専用の context でログインする。
      test.use({ storageState: { cookies: [], origins: [] } });

      test.beforeEach(async ({ page }) => {
        await logIn(page);
        await logoutButton(page).click();
        await expectLoggedOutPage(page);
      });

      test("ログアウト後の画面に axe の違反がない", async ({ expectNoA11yViolations }) => {
        await expectNoA11yViolations("ログアウト後の画面");
      });

      test("ログアウトした後の 404 に axe の違反がない", async ({
        page,
        expectNoA11yViolations,
      }) => {
        await page.goto("/no-such-page");
        await expect(notFoundHeading(page)).toBeVisible();

        await expectNoA11yViolations("ログアウトした後の 404");
      });
    });
  });
}
