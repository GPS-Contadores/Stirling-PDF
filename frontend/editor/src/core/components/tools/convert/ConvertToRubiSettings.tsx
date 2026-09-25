import { Stack, Text, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { ConvertParameters } from "@app/hooks/tools/convert/useConvertParameters";

interface ConvertToRubiSettingsProps {
  parameters: ConvertParameters;
  onParameterChange: <K extends keyof ConvertParameters>(
    key: K,
    value: ConvertParameters[K],
  ) => void;
  disabled?: boolean;
}

/**
 * GPS-Contadores: the RUBI calculation code is decided at import time, so the
 * client's sheet usually comes back with it blank. Typed here, it fills only
 * that blank; a code already in the sheet wins.
 */
const ConvertToRubiSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
}: ConvertToRubiSettingsProps) => {
  const { t } = useTranslation();
  const calculo = parameters.rubiOptions?.calculo ?? "";

  return (
    <Stack gap="sm" data-testid="rubi-settings">
      <Text size="sm" fw={500}>
        {t("convert.rubiOptions", "RUBI import")}:
      </Text>

      <TextInput
        data-testid="rubi-calculo-input"
        label={t("convert.rubiCalculo", "Calculation code")}
        description={t(
          "convert.rubiCalculoDescription",
          "Only used if the sheet left it blank. Digits only.",
        )}
        placeholder="505"
        inputMode="numeric"
        maxLength={5}
        value={calculo}
        onChange={(event) =>
          onParameterChange("rubiOptions", {
            calculo: event.currentTarget.value.replace(/\D/g, ""),
          })
        }
        disabled={disabled}
      />
    </Stack>
  );
};

export default ConvertToRubiSettings;
