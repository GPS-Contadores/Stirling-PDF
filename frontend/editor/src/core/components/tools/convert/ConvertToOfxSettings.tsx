import { Stack, Text, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { ConvertParameters } from "@app/hooks/tools/convert/useConvertParameters";

interface ConvertToOfxSettingsProps {
  parameters: ConvertParameters;
  onParameterChange: <K extends keyof ConvertParameters>(
    key: K,
    value: ConvertParameters[K],
  ) => void;
  disabled?: boolean;
  /** Why the last conversion was refused for the account, shown on the field */
  error?: string | null;
}

/**
 * GPS-Contadores: some statements do not print the account number (the
 * Banco do Brasil one downloaded from the website), and without it Questor
 * cannot tell which account the OFX belongs to. The ofx service then refuses,
 * and the Convert tool reopens these settings with the refusal on the field.
 * Typed, it replaces the number printed on the statement, so it is meant for
 * one statement at a time.
 */
const ConvertToOfxSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
  error = null,
}: ConvertToOfxSettingsProps) => {
  const { t } = useTranslation();
  const conta = parameters.ofxOptions?.conta ?? "";

  return (
    <Stack gap="sm" data-testid="ofx-settings">
      <Text size="sm" fw={500}>
        {t("convert.ofxOptions", "OFX for Questor")}:
      </Text>

      <TextInput
        data-testid="ofx-conta-input"
        label={t("convert.ofxConta", "Account number")}
        description={t(
          "convert.ofxContaDescription",
          "Only for statements that do not print the account, like the Banco do Brasil one downloaded from the website. It replaces the number printed on the statement and is cleared after the conversion.",
        )}
        placeholder="98765-4"
        maxLength={32}
        value={conta}
        error={error || undefined}
        withAsterisk={!!error}
        onChange={(event) =>
          // As typed: dropping a stray character would turn "98765/4" into
          // another account. ConvertPDFToOfx refuses what is not an account,
          // and the refusal comes back on this field.
          onParameterChange("ofxOptions", { conta: event.currentTarget.value })
        }
        disabled={disabled}
      />
    </Stack>
  );
};

export default ConvertToOfxSettings;
