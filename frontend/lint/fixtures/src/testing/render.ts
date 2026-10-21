import { render } from "@testing-library/react";
import type { ReactElement } from "react";

export function renderPage(ui: ReactElement) {
  return render(ui);
}
