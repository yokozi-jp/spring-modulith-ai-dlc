import { getFindCurrentTenantMockHandler } from "@frontend/api/mocks/tenancy/tenancy.msw";
import { setupServer } from "msw/node";

import { tradingTenant } from "./tenancy";

/**
 * 全テストが共有する MSW の server。起動と後片付けは setup.ts が行い、handler はテストごとに server.use() で足す。
 * 業務の画面はテナントを選んだ後に出すため、選んだテナントの API だけは既定で応える。
 */
export const server = setupServer(getFindCurrentTenantMockHandler(tradingTenant));
