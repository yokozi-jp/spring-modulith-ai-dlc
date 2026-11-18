import { render } from "@testing-library/react";
import { http } from "msw";

import { getFindOrderByIdMockHandler } from "@frontend/api/mocks/ordering/ordering.msw";
import { renderPage } from "@/testing/render";

export const testTools = { http, getFindOrderByIdMockHandler, render, renderPage };
