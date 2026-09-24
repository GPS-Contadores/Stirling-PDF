/**
 * React hook for OneDrive / SharePoint (GPS fork): open files through the
 * Microsoft File Picker and save tool results to a folder picked the same way.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { alert } from "@app/components/toast";
import { getOneDriveConfig } from "@app/services/oneDriveConfig";
import {
  acquireToken,
  GRAPH_SCOPES,
  isAuthCancelled,
  prepareOneDriveAuth,
  resourceScopes,
} from "@app/services/oneDriveAuth";
import {
  openOneDrivePicker,
  PickerMode,
} from "@app/services/oneDrivePickerService";
import {
  downloadPickedItems,
  getMyDriveSiteUrl,
  OneDriveNotProvisionedError,
  UploadedItem,
  uploadFileToFolder,
} from "@app/services/oneDriveGraph";

interface OpenPickerOptions {
  multiple?: boolean;
}

interface UseOneDrivePickerReturn {
  isEnabled: boolean;
  isLoading: boolean;
  /** Files picked by the user; [] when cancelled or on error (already reported). */
  openPicker: (options?: OpenPickerOptions) => Promise<File[]>;
  /** Uploads to a folder the user picks; null when cancelled or on error. */
  saveFiles: (files: File[]) => Promise<UploadedItem[] | null>;
}

export function useOneDrivePicker(): UseOneDrivePickerReturn {
  const { t, i18n } = useTranslation();
  const config = useMemo(() => getOneDriveConfig(), []);
  const [isLoading, setIsLoading] = useState(false);
  // The user's OneDrive site doesn't change within a session.
  const siteUrlRef = useRef<string | null>(null);

  useEffect(() => {
    if (config) {
      prepareOneDriveAuth(config).catch((error) =>
        console.error("OneDrive auth init failed:", error),
      );
    }
  }, [config]);

  const pick = useCallback(
    async (mode: PickerMode, multiple: boolean) => {
      if (!config) return [];
      if (!siteUrlRef.current) {
        const graphToken = await acquireToken(config, GRAPH_SCOPES);
        const siteUrl = await getMyDriveSiteUrl(graphToken);
        // The picker page gets a SharePoint token in its form: only ever
        // post it to the configured host.
        if (new URL(siteUrl).origin !== config.pickerBaseUrl) {
          throw new Error(
            `OneDrive at ${siteUrl}, outside ${config.pickerBaseUrl}`,
          );
        }
        siteUrlRef.current = siteUrl;
      }
      return openOneDrivePicker({
        baseUrl: siteUrlRef.current,
        locale: i18n.language.toLowerCase(),
        mode,
        multiple,
        pickLabel:
          mode === "folders" ? t("oneDrive.saveHere", "Save here") : undefined,
        closeLabel: t("close", "Close"),
        getToken: (resource) => acquireToken(config, resourceScopes(resource)),
      });
    },
    [config, i18n.language, t],
  );

  const reportError = useCallback(
    (error: unknown) => {
      if (isAuthCancelled(error)) return;
      console.error("OneDrive error:", error);
      alert({
        alertType: "error",
        title: t("oneDrive.error", "OneDrive error"),
        body:
          error instanceof OneDriveNotProvisionedError
            ? t(
                "oneDrive.notProvisioned",
                "Open OneDrive once at office.com and try again.",
              )
            : error instanceof Error
              ? error.message
              : String(error),
      });
    },
    [t],
  );

  const openPicker = useCallback(
    async (options: OpenPickerOptions = {}): Promise<File[]> => {
      if (!config) return [];
      try {
        const items = await pick("files", options.multiple ?? true);
        if (items.length === 0) return [];
        setIsLoading(true);
        const token = await acquireToken(config, GRAPH_SCOPES);
        return await downloadPickedItems(items, token);
      } catch (error) {
        reportError(error);
        return [];
      } finally {
        setIsLoading(false);
      }
    },
    [config, pick, reportError],
  );

  const saveFiles = useCallback(
    async (files: File[]): Promise<UploadedItem[] | null> => {
      if (!config || files.length === 0) return null;
      try {
        const [folder] = await pick("folders", false);
        if (!folder) return null;
        setIsLoading(true);
        const token = await acquireToken(config, GRAPH_SCOPES);
        const uploaded: UploadedItem[] = [];
        // One at a time: a batch of large PDFs in parallel saturates the uplink.
        for (const file of files) {
          uploaded.push(await uploadFileToFolder(file, folder, token));
        }
        return uploaded;
      } catch (error) {
        reportError(error);
        return null;
      } finally {
        setIsLoading(false);
      }
    },
    [config, pick, reportError],
  );

  return { isEnabled: config !== null, isLoading, openPicker, saveFiles };
}
