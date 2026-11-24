import { render } from "@testing-library/react";
import { http } from "msw";
import { setupServer } from "msw/node";

import { getFindOrderByIdMockHandler } from "@frontend/api/mocks/ordering/ordering.msw";
import { renderPage } from "@/testing/render";

export const testTools = { http, setupServer, getFindOrderByIdMockHandler, render, renderPage };
