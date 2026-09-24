/**
 * Delegated Microsoft tokens for the OneDrive integration (GPS fork).
 *
 * The app sits behind oauth2-proxy, which already signed the user in with the
 * same tenant, so the popup usually closes on its own: Entra reuses the session.
 * MSAL v5 returns the popup result through msal-redirect.html (see that file).
 */

import type { PublicClientApplication } from "@azure/msal-browser";
import type { OneDriveConfig } from "@app/services/oneDriveConfig";

/** Graph scopes for reading picked files and uploading results. */
export const GRAPH_SCOPES = ["https://graph.microsoft.com/Files.ReadWrite.All"];

const REDIRECT_PAGE = "msal-redirect.html";

let clientPromise: Promise<PublicClientApplication> | null = null;

// MSAL is loaded on demand: builds without the OneDrive variables never
// download it, and those with them fetch it when the hook mounts.
async function createClient(
  config: OneDriveConfig,
): Promise<PublicClientApplication> {
  const { PublicClientApplication } = await import("@azure/msal-browser");
  const client = new PublicClientApplication({
    auth: {
      clientId: config.clientId,
      authority: `https://login.microsoftonline.com/${config.tenant}`,
      // Resolved against <base href>, so it also works under RUN_SUBPATH.
      redirectUri: new URL(REDIRECT_PAGE, document.baseURI).href,
    },
    cache: { cacheLocation: "sessionStorage" },
  });
  await client.initialize();
  return client;
}

function getClient(config: OneDriveConfig): Promise<PublicClientApplication> {
  if (!clientPromise) {
    clientPromise = createClient(config);
    clientPromise.catch(() => {
      clientPromise = null;
    });
  }
  return clientPromise;
}

/**
 * Initialise MSAL ahead of the click. Browsers only allow the login popup
 * shortly after a user gesture, so it must not wait on initialisation.
 */
export function prepareOneDriveAuth(config: OneDriveConfig): Promise<void> {
  return getClient(config).then(() => undefined);
}

/** Scope for a resource the picker asks for: SharePoint hosts, sometimes Graph. */
export function resourceScopes(resource: string): string[] {
  return [`${new URL(resource).origin}/.default`];
}

export async function acquireToken(
  config: OneDriveConfig,
  scopes: string[],
): Promise<string> {
  const client = await getClient(config);
  const account = client.getActiveAccount() ?? client.getAllAccounts()[0];

  if (account) {
    try {
      const result = await client.acquireTokenSilent({ scopes, account });
      return result.accessToken;
    } catch {
      // Expired refresh token, new consent, blocked iframe: all end in the popup.
    }
  }

  const result = await client.acquireTokenPopup({ scopes, account });
  client.setActiveAccount(result.account);
  return result.accessToken;
}

/** The user closed the Microsoft popup: a cancel, not an error to report. */
// Compared by code (BrowserAuthErrorCodes.userCancelled) so this module
// doesn't import MSAL statically.
export function isAuthCancelled(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { errorCode?: unknown }).errorCode === "user_cancelled"
  );
}
