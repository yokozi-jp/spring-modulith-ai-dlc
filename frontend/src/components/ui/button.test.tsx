import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vite-plus/test";

import { Button } from "./button";

describe("Button", () => {
  it("renders a native button with its accessible name", () => {
    const markup = renderToStaticMarkup(<Button type="button">Save</Button>);

    expect(markup).toContain("<button");
    expect(markup).toContain('data-slot="button"');
    expect(markup).toContain(">Save</button>");
  });
});
