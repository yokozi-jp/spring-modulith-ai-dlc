import { render } from "@testing-library/react";
import { http } from "msw";

import { orderMock } from "@/api/generated/mocks/order";
import { renderPage } from "@/testing/render";

export const testTools = { http, orderMock, render, renderPage };
