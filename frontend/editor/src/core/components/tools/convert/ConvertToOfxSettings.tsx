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
}

/**
 * GPS-Contadores: some statements do not print the account number (the
 * Banco do Brasil one downloaded from the website), and without it Questor
 * cannot tell which account the OFX belongs to. The ofx service then refuses
 * and the error points here. Typed, it replaces the number printed on the
 * statement, so it is meant for one statement at a time.
 */
const ConvertToOfxSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
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
          "Only for statements that do not print the account, like the Banco do Brasil one downloaded from the website. It replaces the number printed on the statement.",
        )}
        placeholder="27346-5"
        maxLength={32}
        value={conta}
        onChange={(event) =>
          onParameterChange("ofxOptions", {
            // The shape the ofx service accepts: digits, dot, hyphen, space
            // and the check digit X.
            conta: event.currentTarget.value.replace(/[^\d.\- xX]/g, ""),
          })
        }
        disabled={disabled}
      />
    </Stack>
  );
};

export default ConvertToOfxSettings;
