import { test as setup } from "./fixtures";
import { resetPaymentGatewayStubs } from "./payment-gateway";

setup("決済代行の WireMock を共有のスタブだけに戻す", async ({ request }) => {
  await resetPaymentGatewayStubs(request);
});
