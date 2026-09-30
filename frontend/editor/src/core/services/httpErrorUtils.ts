import axios from "axios";
import { t } from "@app/services/errorTranslation";

const MAX_TOAST_BODY_CHARS = 400; // avoid massive, unreadable toasts

// Resolved on each call, not at import: the language can change at runtime.
const friendlyFallback = (): string =>
  t("errors.fallback", "There was an error processing your request.");
const corruptedFilesMessage = (): string =>
  t(
    "errors.invalidOrCorruptedFiles",
    "Process failed due to invalid/corrupted file(s)",
  );

export function clampText(s: string, max = MAX_TOAST_BODY_CHARS): string {
  return s && s.length > max ? `${s.slice(0, max)}…` : s;
}

function isUnhelpfulMessage(msg: string | null | undefined): boolean {
  const s = (msg || "").trim();
  if (!s) return true;
  // Common unhelpful payloads we see
  if (s === "{}" || s === "[]") return true;
  if (/^request failed/i.test(s)) return true;
  if (/^network error/i.test(s)) return true;
  if (/^[45]\d\d\b/.test(s)) return true; // "500 Server Error" etc.
  return false;
}

export function titleForStatus(status?: number): string {
  if (!status) return t("errors.networkError", "Network error");
  if (status >= 500) return t("errors.serverError", "Server error");
  if (status >= 400) return t("errors.requestError", "Request error");
  return t("errors.requestFailed", "Request failed");
}

/**
 * The server's message in the user's language, looked up by the ProblemDetail
 * `errorCode` (ExceptionUtils.ErrorCode in the backend, whose messages exist
 * only in English). One literal key per code so the translation audits can see
 * them. Codes whose message carries case data (`{0}`: a file id, a page, a DPI)
 * are left out on purpose: the server's text keeps that information.
 */
export function translatedErrorCode(code: unknown): string | undefined {
  switch (code) {
    case "E001":
      return t(
        "errors.code.E001",
        "PDF file appears to be corrupted or damaged. Please try using the 'Repair PDF' feature first to fix the file before proceeding with this operation.",
      );
    case "E002":
      return t(
        "errors.code.E002",
        "One or more PDF files appear to be corrupted or damaged. Please try using the 'Repair PDF' feature on each file first before attempting to merge them.",
      );
    case "E003":
      return t(
        "errors.code.E003",
        "The PDF appears to have corrupted encryption data. This can happen when the PDF was created with incompatible encryption methods. Please try using the 'Repair PDF' feature first, or contact the document creator for a new copy.",
      );
    case "E004":
      return t(
        "errors.code.E004",
        "The PDF Document is passworded and either the password was not provided or was incorrect",
      );
    case "E005":
      return t("errors.code.E005", "PDF file contains no pages");
    case "E006":
      return t("errors.code.E006", "File must be in PDF format");
    case "E010":
      return t(
        "errors.code.E010",
        "Invalid or corrupted CBR/RAR archive. The file may be corrupted, use an unsupported RAR format (RAR5+), encrypted, or may not be a valid RAR archive.",
      );
    case "E012":
      return t(
        "errors.code.E012",
        "No valid images found in the CBR file. The archive may be empty, or all images may be corrupted or in unsupported formats.",
      );
    case "E014":
      return t("errors.code.E014", "File must be a CBR or RAR archive");
    case "E015":
      return t(
        "errors.code.E015",
        "Invalid or corrupted CBZ/ZIP archive. The file may be empty, corrupted, or may not be a valid ZIP archive.",
      );
    case "E016":
      return t(
        "errors.code.E016",
        "No valid images found in the CBZ file. The archive may be empty, or all images may be corrupted or in unsupported formats.",
      );
    case "E018":
      return t("errors.code.E018", "File must be a CBZ or ZIP archive");
    case "E020":
      return t("errors.code.E020", "EML file is empty or null");
    case "E021":
      return t("errors.code.E021", "Invalid EML file format");
    case "E032":
      return t("errors.code.E032", "File cannot be null or empty");
    case "E033":
      return t("errors.code.E033", "File must have a name");
    case "E040":
      return t("errors.code.E040", "OCR language options are not specified");
    case "E041":
      return t(
        "errors.code.E041",
        "Invalid OCR languages format: none of the selected languages are valid",
      );
    case "E042":
      return t("errors.code.E042", "OCR tools are not installed");
    case "E043":
      return t(
        "errors.code.E043",
        "Invalid OCR render type. Must be 'hocr' or 'sandwich'",
      );
    case "E050":
      return t(
        "errors.code.E050",
        "Compression options are not specified (expected output size and optimize level)",
      );
    case "E051":
      return t("errors.code.E051", "Ghostscript compression command failed");
    case "E052":
      return t("errors.code.E052", "QPDF command failed");
    case "E060":
      return t("errors.code.E060", "PDF/A conversion failed");
    case "E061":
      return t("errors.code.E061", "File must be in HTML or ZIP format");
    case "E062":
      return t("errors.code.E062", "Python is required for WebP conversion");
    case "E063":
      return t(
        "errors.code.E063",
        "FFmpeg must be installed to convert PDFs to video. Install FFmpeg and ensure it is available on the system PATH.",
      );
    case "E073":
      return t(
        "errors.code.E073",
        "Invalid comparator format: only 'greater', 'equal', and 'less' are supported",
      );
    case "E080":
      return t("errors.code.E080", "MD5 algorithm not available");
    default:
      return undefined;
  }
}

/**
 * `data` overrides `error.response.data`. Tool requests use
 * `responseType: "blob"`, so the body arrives as a Blob that can only be read
 * asynchronously: pass the result of `normalizeAxiosErrorData` here, or a
 * ProblemDetail becomes "{}" and the user only sees the generic fallback.
 */
export function extractAxiosErrorMessage(
  error: any,
  data: any = error?.response?.data,
): {
  title: string;
  body: string;
} {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const _statusText = error.response?.statusText || "";
    let parsed: any = undefined;
    const raw = data;
    if (typeof raw === "string") {
      try {
        parsed = JSON.parse(raw);
      } catch {
        /* keep as string */
      }
    } else {
      parsed = raw;
    }
    const extractIds = (): string[] | undefined => {
      if (Array.isArray(parsed?.errorFileIds))
        return parsed.errorFileIds as string[];
      const rawText = typeof raw === "string" ? raw : "";
      const uuidMatches = rawText.match(
        /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g,
      );
      return uuidMatches && uuidMatches.length > 0
        ? Array.from(new Set(uuidMatches))
        : undefined;
    };

    const body = ((): string => {
      const data = parsed;
      if (!data) return typeof raw === "string" ? raw : "";
      const ids = extractIds();
      if (ids && ids.length > 0) return `Failed files: ${ids.join(", ")}`;
      const translated = translatedErrorCode(data?.errorCode);
      if (translated) return translated;
      if (data?.message) return data.message as string;
      // Spring ProblemDetail (GlobalExceptionHandler) carries it in `detail`.
      if (typeof data?.detail === "string") return data.detail;
      if (typeof raw === "string") return raw;
      try {
        return JSON.stringify(data);
      } catch {
        return "";
      }
    })();
    const ids = extractIds();
    const title = titleForStatus(status);
    if (ids && ids.length > 0) {
      return { title, body: corruptedFilesMessage() };
    }
    if (status === 422) {
      const bodyMsg = isUnhelpfulMessage(body) ? corruptedFilesMessage() : body;
      return { title, body: bodyMsg };
    }
    const bodyMsg = isUnhelpfulMessage(body) ? friendlyFallback() : body;
    return { title, body: bodyMsg };
  }
  try {
    const msg = (error?.message || String(error)) as string;
    return {
      title: titleForStatus(undefined),
      body: isUnhelpfulMessage(msg) ? friendlyFallback() : msg,
    };
  } catch (e) {
    // ignore extraction errors
    console.debug("extractAxiosErrorMessage", e);
    return { title: titleForStatus(undefined), body: friendlyFallback() };
  }
}
