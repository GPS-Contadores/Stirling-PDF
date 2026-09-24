/**
 * Entry of msal-redirect.html, the redirect URI registered in Entra ID for the
 * OneDrive integration (GPS fork). MSAL v5 no longer reads the popup's URL from
 * the opener: this page hands the response back to the app window and closes.
 * Kept as its own tiny bundle so the popup doesn't load the whole editor.
 */

import { broadcastResponseToMainFrame } from "@azure/msal-browser/redirect-bridge";

broadcastResponseToMainFrame().catch((error) => {
  console.error("Microsoft sign-in response could not be delivered:", error);
});
