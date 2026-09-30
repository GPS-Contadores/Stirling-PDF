import { alert } from "@app/components/toast";
import { t } from "@app/services/errorTranslation";
import { titleForStatus } from "@app/services/httpErrorUtils";

interface ErrorToastMapping {
  regex: RegExp;
  message: () => string;
}

// Centralized list of special backend error message patterns → friendly, translated toasts.
// One literal t() per message so the translation audits can see the keys;
// the old `globalThis.i18next` lookup never existed, so these were always English.
const MAPPINGS: ErrorToastMapping[] = [
  {
    regex: /pdf contains an encryption dictionary/i,
    message: () =>
      t(
        "errors.encryptedPdfMustRemovePassword",
        "This PDF is encrypted. Please unlock it using the Unlock PDF Forms tool.",
      ),
  },
  {
    regex:
      /the pdf document is passworded and either the password was not provided or was incorrect/i,
    message: () =>
      t(
        "errors.incorrectPasswordProvided",
        "The PDF password is incorrect or not provided.",
      ),
  },
];

/**
 * Match a raw backend error string against known patterns and show a friendly toast.
 * Returns true if a special toast was shown, false otherwise.
 */
export function showSpecialErrorToast(
  rawError: string | undefined,
  options?: { status?: number },
): boolean {
  const message = (rawError || "").toString();
  if (!message) return false;

  for (const mapping of MAPPINGS) {
    if (mapping.regex.test(message)) {
      alert({
        alertType: "error",
        title: titleForStatus(options?.status),
        body: mapping.message(),
        expandable: true,
        isPersistentPopup: false,
      });
      return true;
    }
  }
  return false;
}
