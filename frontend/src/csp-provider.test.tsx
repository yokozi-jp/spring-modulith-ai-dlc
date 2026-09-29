import { CSPProvider } from "@base-ui/react/csp-provider";
import { ScrollArea } from "@base-ui/react/scroll-area";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vite-plus/test";

function ScrollAreaFixture() {
  return (
    <ScrollArea.Root>
      <ScrollArea.Viewport>
        <ScrollArea.Content>content</ScrollArea.Content>
      </ScrollArea.Viewport>
    </ScrollArea.Root>
  );
}

describe("Base UI CSP configuration", () => {
  it("disables inline style elements", () => {
    const defaultMarkup = renderToStaticMarkup(<ScrollAreaFixture />);
    const strictCspMarkup = renderToStaticMarkup(
      <CSPProvider disableStyleElements>
        <ScrollAreaFixture />
      </CSPProvider>,
    );

    expect(defaultMarkup).toContain("<style");
    expect(strictCspMarkup).not.toContain("<style");
  });
});
