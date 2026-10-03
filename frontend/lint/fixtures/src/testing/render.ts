import { render } from "@testing-library/react";
import type { ReactElement } from "react";

export const renderPage = (ui: ReactElement) => render(ui);
