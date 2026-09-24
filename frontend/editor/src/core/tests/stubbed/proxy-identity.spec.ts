import { test, expect } from "@app/tests/helpers/stub-test-base";

/**
 * Login disabled + oauth2-proxy at the edge (GPS deploy): the sidebar shows
 * who the proxy says is signed in and offers the proxy's sign-out. Without
 * the proxy it keeps the generic "User" label.
 */

test.use({ stubOptions: { enableLogin: false }, autoGoto: false });

const bottomName = ".file-sidebar-bottom-name";

test("shows the proxy identity and signs out through the proxy", async ({
  page,
}) => {
  await page.route("**/oauth2/userinfo", (route) =>
    route.fulfill({
      json: {
        user: "a1b2c3-subject",
        email: "lorenzo@gestao.com.br",
        preferredUsername: "lorenzo@gestao.com.br",
      },
    }),
  );
  await page.goto("/", { waitUntil: "domcontentloaded" });

  await expect(page.locator(bottomName)).toHaveText("lorenzo@gestao.com.br");
  const signOut = page.getByTestId("proxy-sign-out");
  await expect(signOut).toBeVisible();
  await expect(signOut).toHaveAttribute("href", "/oauth2/sign_out");
});

test("keeps the generic label when there is no proxy", async ({ page }) => {
  await page.route("**/oauth2/userinfo", (route) =>
    route.fulfill({ status: 404, body: "Not Found" }),
  );
  await page.goto("/", { waitUntil: "domcontentloaded" });

  await expect(page.locator(bottomName)).toHaveText("User");
  await expect(page.getByTestId("proxy-sign-out")).toHaveCount(0);
});

test("ignores the SPA fallback page served on the userinfo path", async ({
  page,
}) => {
  await page.route("**/oauth2/userinfo", (route) =>
    route.fulfill({
      status: 200,
      contentType: "text/html",
      body: "<!doctype html><html><body></body></html>",
    }),
  );
  await page.goto("/", { waitUntil: "domcontentloaded" });

  await expect(page.locator(bottomName)).toHaveText("User");
  await expect(page.getByTestId("proxy-sign-out")).toHaveCount(0);
});
