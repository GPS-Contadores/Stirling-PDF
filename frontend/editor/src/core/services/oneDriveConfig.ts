/**
 * OneDrive / SharePoint configuration (GPS fork).
 *
 * Comes from build-time variables only. Without all three the integration is
 * off and its buttons are not rendered, so builds that don't set them behave
 * exactly like upstream.
 */

export interface OneDriveConfig {
  /** Entra ID app registration (SPA platform) used to get delegated tokens. */
  clientId: string;
  /** Tenant id or verified domain, e.g. "contoso.onmicrosoft.com". */
  tenant: string;
  /**
   * Origin of the tenant's OneDrive, e.g. "https://contoso-my.sharepoint.com".
   * The picker opens on the user's site under it (".../personal/<user>"),
   * never on this root; the origin bounds where tokens and messages may go.
   */
  pickerBaseUrl: string;
}

type OneDriveEnv = Partial<
  Record<
    "VITE_GPS_ENTRA_CLIENT_ID" | "VITE_GPS_TENANT" | "VITE_GPS_SHAREPOINT_HOST",
    string | undefined
  >
>;

/** Accepts "contoso-my.sharepoint.com" or a full https URL; returns its origin. */
function toHttpsOrigin(host: string): string | null {
  try {
    const url = new URL(host.includes("://") ? host : `https://${host}`);
    return url.protocol === "https:" ? url.origin : null;
  } catch {
    return null;
  }
}

export function getOneDriveConfig(
  env: OneDriveEnv = import.meta.env,
): OneDriveConfig | null {
  const clientId = env.VITE_GPS_ENTRA_CLIENT_ID?.trim();
  const tenant = env.VITE_GPS_TENANT?.trim();
  const host = env.VITE_GPS_SHAREPOINT_HOST?.trim();
  if (!clientId || !tenant || !host) return null;

  const pickerBaseUrl = toHttpsOrigin(host);
  if (!pickerBaseUrl) return null;

  return { clientId, tenant, pickerBaseUrl };
}
