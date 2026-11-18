import { setupServer } from "msw/node";

/** 全テストが共有する MSW の server。起動と後片付けは setup.ts が行い、handler はテストごとに server.use() で足す。 */
export const server = setupServer();
