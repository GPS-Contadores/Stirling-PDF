/**
 * Microsoft Graph calls for the OneDrive integration (GPS fork): download the
 * files picked in the File Picker and upload tool results to a picked folder.
 */

import type { PickedItem } from "@app/services/oneDrivePickerService";

const GRAPH = "https://graph.microsoft.com/v1.0";

/** Graph's advice: simple PUT below 4 MiB, upload session above. */
export const SIMPLE_UPLOAD_LIMIT = 4 * 1024 * 1024;
/** Upload session chunks must be multiples of 320 KiB; this is 10 MiB. */
export const UPLOAD_CHUNK_SIZE = 32 * 320 * 1024;

type Fetch = typeof fetch;

interface DriveItem {
  id: string;
  name: string;
  webUrl?: string;
  lastModifiedDateTime?: string;
  file?: { mimeType?: string };
  "@microsoft.graph.downloadUrl"?: string;
}

export interface UploadedItem {
  name: string;
  webUrl?: string;
}

function itemUrl(driveId: string, itemId: string): string {
  return `${GRAPH}/drives/${encodeURIComponent(driveId)}/items/${encodeURIComponent(itemId)}`;
}

async function ensureOk(response: Response, what: string): Promise<Response> {
  if (response.ok) return response;
  let detail = "";
  try {
    const body = await response.json();
    detail = body?.error?.message ?? "";
  } catch {
    // Body is not JSON; the status is all we have.
  }
  throw new Error(
    `${what}: HTTP ${response.status}${detail ? ` — ${detail}` : ""}`,
  );
}

/** Graph has no OneDrive for this user: it is created the first time they open it. */
export class OneDriveNotProvisionedError extends Error {
  constructor() {
    super("OneDrive not provisioned for this user");
    this.name = "OneDriveNotProvisionedError";
  }
}

/**
 * Site of a OneDrive library from its webUrl: the library is the last path
 * segment. ".../personal/ana_contoso_com/Documents" →
 * ".../personal/ana_contoso_com".
 */
export function siteUrlFromDriveWebUrl(webUrl: string): string {
  const url = new URL(webUrl);
  const segments = url.pathname.split("/").filter(Boolean);
  if (segments.length < 2) {
    throw new Error(`Unexpected OneDrive address: ${webUrl}`);
  }
  segments.pop();
  return `${url.origin}/${segments.join("/")}`;
}

/**
 * Site of the signed-in user's OneDrive, where the File Picker must be opened.
 * The root of the "-my" host is the My Site Host collection and holds nobody's
 * library: opened there, the picker answers "the specified list is invalid".
 */
export async function getMyDriveSiteUrl(
  token: string,
  fetchImpl: Fetch = fetch,
): Promise<string> {
  const response = await fetchImpl(`${GRAPH}/me/drive?$select=webUrl`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (response.status === 404) throw new OneDriveNotProvisionedError();
  await ensureOk(response, "OneDrive of the signed-in user");
  const { webUrl } = (await response.json()) as { webUrl?: string };
  if (!webUrl) throw new Error("Graph returned the OneDrive without webUrl");
  return siteUrlFromDriveWebUrl(webUrl);
}

/**
 * Downloads picked items as Files. Uses the item's pre-authenticated
 * downloadUrl rather than `/content`: `/content` answers with a cross-origin
 * redirect, and browsers drop the Authorization header on those.
 */
export async function downloadPickedItems(
  items: PickedItem[],
  token: string,
  fetchImpl: Fetch = fetch,
): Promise<File[]> {
  return Promise.all(
    items.map(async (picked) => {
      const metaResponse = await fetchImpl(
        itemUrl(picked.parentReference.driveId, picked.id),
        { headers: { Authorization: `Bearer ${token}` } },
      );
      await ensureOk(metaResponse, `OneDrive item ${picked.name ?? picked.id}`);
      const item = (await metaResponse.json()) as DriveItem;

      const downloadUrl = item["@microsoft.graph.downloadUrl"];
      if (!item.file || !downloadUrl) {
        throw new Error(`"${item.name}" is not a file that can be downloaded`);
      }

      const contentResponse = await fetchImpl(downloadUrl);
      await ensureOk(contentResponse, `Download of "${item.name}"`);
      const blob = await contentResponse.blob();

      const lastModified = item.lastModifiedDateTime
        ? Date.parse(item.lastModifiedDateTime)
        : Date.now();
      return new File([blob], item.name, {
        type: item.file.mimeType || blob.type,
        lastModified,
      });
    }),
  );
}

/**
 * Uploads `file` into `folder`. An existing file with the same name is never
 * overwritten: OneDrive renames the new one ("name 1.pdf").
 */
export async function uploadFileToFolder(
  file: File,
  folder: PickedItem,
  token: string,
  fetchImpl: Fetch = fetch,
): Promise<UploadedItem> {
  const base = `${itemUrl(folder.parentReference.driveId, folder.id)}:/${encodeURIComponent(file.name)}:`;

  if (file.size <= SIMPLE_UPLOAD_LIMIT) {
    const response = await fetchImpl(
      `${base}/content?@microsoft.graph.conflictBehavior=rename`,
      {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": file.type || "application/octet-stream",
        },
        body: file,
      },
    );
    await ensureOk(response, `Upload of "${file.name}"`);
    return toUploaded(await response.json());
  }

  const sessionResponse = await fetchImpl(`${base}/createUploadSession`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      item: { "@microsoft.graph.conflictBehavior": "rename" },
    }),
  });
  await ensureOk(sessionResponse, `Upload of "${file.name}"`);
  const { uploadUrl } = (await sessionResponse.json()) as {
    uploadUrl: string;
  };

  // uploadUrl is pre-authenticated; sending Authorization to it causes a 401.
  let last: Response | null = null;
  for (let start = 0; start < file.size; start += UPLOAD_CHUNK_SIZE) {
    const end = Math.min(start + UPLOAD_CHUNK_SIZE, file.size);
    last = await fetchImpl(uploadUrl, {
      method: "PUT",
      headers: { "Content-Range": `bytes ${start}-${end - 1}/${file.size}` },
      body: file.slice(start, end),
    });
    await ensureOk(last, `Upload of "${file.name}"`);
  }
  return toUploaded(await last!.json());
}

function toUploaded(item: DriveItem): UploadedItem {
  return { name: item.name, webUrl: item.webUrl };
}
