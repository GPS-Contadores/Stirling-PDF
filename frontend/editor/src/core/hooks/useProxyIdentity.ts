import { useEffect, useState } from "react";

/**
 * Identity known by an oauth2-proxy sitting in front of Stirling.
 *
 * GPS deploys Stirling with login disabled and authenticates at the edge
 * (oauth2-proxy + Entra ID), so the auth context only ever sees an anonymous
 * "User". The proxy serves `/oauth2/*` on the same host and knows who is
 * signed in. This is display only: no session is created and nothing is sent
 * to the backend.
 */
export interface ProxyIdentity {
  /** Text to show in place of the generic "User" label. */
  displayName: string;
  email: string | null;
}

export const PROXY_USERINFO_PATH = "/oauth2/userinfo";
export const PROXY_SIGN_OUT_PATH = "/oauth2/sign_out";

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

/**
 * Parses the oauth2-proxy userinfo payload
 * (`{ user, email, preferredUsername?, additionalClaims? }`). Returns null
 * for `{}` (proxy without a session) or anything that doesn't look like it.
 */
export function parseProxyUserinfo(data: unknown): ProxyIdentity | null {
  if (!data || typeof data !== "object" || Array.isArray(data)) return null;
  const info = data as Record<string, unknown>;
  const claims =
    info.additionalClaims && typeof info.additionalClaims === "object"
      ? (info.additionalClaims as Record<string, unknown>)
      : {};

  const email = nonEmptyString(info.email);
  // `user` is the OIDC subject for Entra - an opaque id, so it comes last.
  const displayName =
    nonEmptyString(claims.name) ??
    email ??
    nonEmptyString(info.preferredUsername) ??
    nonEmptyString(info.user);

  return displayName ? { displayName, email } : null;
}

export async function fetchProxyIdentity(
  signal?: AbortSignal,
): Promise<ProxyIdentity | null> {
  try {
    const response = await fetch(PROXY_USERINFO_PATH, {
      credentials: "same-origin",
      headers: { Accept: "application/json" },
      signal,
    });
    if (!response.ok) return null;
    // Without the proxy (vite dev, bare Stirling) the path falls through to
    // the SPA's index.html with a 200, so the status alone proves nothing.
    const contentType = response.headers.get("content-type") ?? "";
    if (!contentType.includes("json")) return null;
    return parseProxyUserinfo(await response.json());
  } catch {
    return null;
  }
}

/**
 * Fetches the proxy identity once while `enabled` is true (pass
 * `config.enableLogin === false`). Resolves to null when there is no proxy,
 * so callers keep their current behaviour.
 */
export function useProxyIdentity(enabled: boolean): ProxyIdentity | null {
  const [identity, setIdentity] = useState<ProxyIdentity | null>(null);

  useEffect(() => {
    if (!enabled) {
      setIdentity(null);
      return;
    }
    const controller = new AbortController();
    void fetchProxyIdentity(controller.signal).then((result) => {
      if (!controller.signal.aborted) setIdentity(result);
    });
    return () => controller.abort();
  }, [enabled]);

  return identity;
}
