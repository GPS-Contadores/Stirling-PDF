import { useEffect, useState } from "react";
import { ConvertParameters } from "@app/hooks/tools/convert/useConvertParameters";
import { ofxAccountRefusal } from "@app/hooks/tools/convert/useConvertOperation";

interface OfxAccountGuardOptions {
  parameters: ConvertParameters;
  updateParameter: <K extends keyof ConvertParameters>(
    key: K,
    value: ConvertParameters[K],
  ) => void;
  /** The Convert tool's last error */
  errorMessage: string | null | undefined;
  /** How many files the last conversion produced */
  convertedCount: number;
  /** Changes whenever the user selects other files */
  selectionKey: string;
  /** Reopens the settings step (clears the error from the results) */
  reopenSettings: () => void;
}

/**
 * GPS-Contadores: the account number typed for a PDF → OFX conversion.
 *
 * - Refused for the account (ofxAccountRefusal): the settings collapse on
 *   error and reopening them clears it, so the message would be gone by the
 *   time the field is visible. Reopen them and return the message for the
 *   field instead.
 * - The typed account replaces the one printed on the statement and belongs
 *   to that statement only. Kept for the next file, a statement that prints
 *   its own account would come out under the typed one, balance check
 *   passed, with no warning. So it is forgotten when the selection changes
 *   and after a conversion that produced files.
 *
 * Returns the refusal to show on the account field, or null.
 */
export const useOfxAccountGuard = ({
  parameters,
  updateParameter,
  errorMessage,
  convertedCount,
  selectionKey,
  reopenSettings,
}: OfxAccountGuardOptions): string | null => {
  const [accountError, setAccountError] = useState<string | null>(null);
  const isPdfToOfx =
    parameters.fromExtension === "pdf" && parameters.toExtension === "ofx";
  const conta = parameters.ofxOptions?.conta ?? "";

  useEffect(() => {
    if (isPdfToOfx && ofxAccountRefusal(errorMessage)) {
      setAccountError(errorMessage ?? null);
      reopenSettings();
    }
  }, [errorMessage]);

  useEffect(() => {
    setAccountError(null);
  }, [conta, parameters.fromExtension, parameters.toExtension]);

  const forgetAccount = () => {
    setAccountError(null);
    if (conta) updateParameter("ofxOptions", { conta: "" });
  };

  useEffect(forgetAccount, [selectionKey]);

  useEffect(() => {
    if (convertedCount > 0) forgetAccount();
  }, [convertedCount]);

  return accountError;
};
