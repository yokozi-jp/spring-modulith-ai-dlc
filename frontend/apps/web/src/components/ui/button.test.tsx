import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vite-plus/test";

import { Button } from "./button";

describe("Button", () => {
  it("子の文字列を accessible name にした、ネイティブの button 要素を描画する", () => {
    const markup = renderToStaticMarkup(<Button type="button">Save</Button>);

    expect(markup).toContain("<button");
    expect(markup).toContain('data-slot="button"');
    expect(markup).toContain(">Save</button>");
  });
});
