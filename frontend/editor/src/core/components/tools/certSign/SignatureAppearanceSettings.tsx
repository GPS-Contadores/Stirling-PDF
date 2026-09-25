import { useEffect } from "react";
import {
  Stack,
  Text,
  Button,
  TextInput,
  NumberInput,
  Group,
  ActionIcon,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import RestartAltIcon from "@mui/icons-material/RestartAlt";
import { CertSignParameters } from "@app/hooks/tools/certSign/useCertSignParameters";

interface SignatureAppearanceSettingsProps {
  parameters: CertSignParameters;
  onParameterChange: (key: keyof CertSignParameters, value: any) => void;
  disabled?: boolean;
  /** Pages in the (shortest) selected document, when known. */
  pageCount?: number;
  /** The area is being drawn in the main viewer; absent in automation settings. */
  areaInViewer?: boolean;
  viewerOpen?: boolean;
  onOpenViewer?: () => void;
  /** The user typed a page number, so the viewer can show that page. */
  onPageChosen?: (page: number) => void;
}

const SignatureAppearanceSettings = ({
  parameters,
  onParameterChange,
  disabled = false,
  pageCount,
  areaInViewer = false,
  viewerOpen = false,
  onOpenViewer,
  onPageChosen,
}: SignatureAppearanceSettingsProps) => {
  const { t } = useTranslation();

  // The backend cannot place a signature past the last page.
  useEffect(() => {
    if (pageCount && parameters.pageNumber > pageCount) {
      onParameterChange("pageNumber", pageCount);
    }
  }, [pageCount, parameters.pageNumber, onParameterChange]);

  return (
    <Stack gap="md">
      {/* Signature Visibility */}
      <Stack gap="sm">
        <div style={{ display: "flex", gap: "4px" }}>
          <Button
            variant={!parameters.showSignature ? "filled" : "outline"}
            color={!parameters.showSignature ? "blue" : "var(--text-muted)"}
            onClick={() => onParameterChange("showSignature", false)}
            disabled={disabled}
            style={{
              flex: 1,
              height: "auto",
              minHeight: "40px",
              fontSize: "11px",
            }}
          >
            <div
              style={{
                textAlign: "center",
                lineHeight: "1.1",
                fontSize: "11px",
              }}
            >
              {t("certSign.appearance.invisible", "Invisible")}
            </div>
          </Button>
          <Button
            variant={parameters.showSignature ? "filled" : "outline"}
            color={parameters.showSignature ? "blue" : "var(--text-muted)"}
            onClick={() => onParameterChange("showSignature", true)}
            disabled={disabled}
            style={{
              flex: 1,
              height: "auto",
              minHeight: "40px",
              fontSize: "11px",
            }}
          >
            <div
              style={{
                textAlign: "center",
                lineHeight: "1.1",
                fontSize: "11px",
              }}
            >
              {t("certSign.appearance.visible", "Visible")}
            </div>
          </Button>
        </div>
      </Stack>

      {/* Visible Signature Options */}
      {parameters.showSignature && (
        <Stack gap="sm">
          <Text size="sm" fw={500}>
            {t("certSign.appearance.options.title", "Signature Details")}
          </Text>
          <TextInput
            label={t("certSign.reason", "Reason")}
            value={parameters.reason}
            onChange={(event) =>
              onParameterChange("reason", event.currentTarget.value)
            }
            disabled={disabled}
          />
          <TextInput
            label={t("certSign.location", "Location")}
            value={parameters.location}
            onChange={(event) =>
              onParameterChange("location", event.currentTarget.value)
            }
            disabled={disabled}
          />
          <TextInput
            label={t("certSign.name", "Name")}
            value={parameters.name}
            onChange={(event) =>
              onParameterChange("name", event.currentTarget.value)
            }
            disabled={disabled}
          />
          <NumberInput
            label={t("certSign.pageNumber", "Page Number")}
            value={parameters.pageNumber}
            onChange={(value) => {
              const page = Number(value) || 1;
              onParameterChange("pageNumber", page);
              onPageChosen?.(page);
            }}
            min={1}
            max={pageCount}
            disabled={disabled}
          />
          {areaInViewer && (
            <Stack gap="xs">
              <Group justify="space-between" align="center" wrap="nowrap">
                <Text size="sm" fw={500}>
                  {t("certSign.appearance.area.title", "Signature position")}
                </Text>
                <ActionIcon
                  variant="outline"
                  onClick={() => onParameterChange("signatureArea", null)}
                  disabled={disabled || !parameters.signatureArea}
                  title={t(
                    "certSign.appearance.area.reset",
                    "Use default position",
                  )}
                  aria-label={t(
                    "certSign.appearance.area.reset",
                    "Use default position",
                  )}
                >
                  <RestartAltIcon style={{ fontSize: "1rem" }} />
                </ActionIcon>
              </Group>
              <Text size="xs" c="dimmed">
                {parameters.signatureArea
                  ? t(
                      "certSign.appearance.area.hintChosen",
                      "The signature goes in the box on page {{page}}. Drag it to move it, or its corners to resize it; draw on another page to move it there.",
                      { page: parameters.pageNumber },
                    )
                  : t(
                      "certSign.appearance.area.hint",
                      "In the viewer, draw a box on the page where the signature should appear, or click to drop one. Without one, the signature goes to the default position.",
                    )}
              </Text>
              {!viewerOpen && onOpenViewer && (
                <Button
                  variant="light"
                  size="xs"
                  onClick={onOpenViewer}
                  disabled={disabled}
                >
                  {t("certSign.appearance.area.openViewer", "Open the viewer")}
                </Button>
              )}
            </Stack>
          )}
          <Stack gap="xs">
            <Text size="sm" fw={500}>
              {t("certSign.logoTitle", "Logo")}
            </Text>
            <div style={{ display: "flex", gap: "4px" }}>
              <Button
                variant={!parameters.showLogo ? "filled" : "outline"}
                color={!parameters.showLogo ? "blue" : "var(--text-muted)"}
                onClick={() => onParameterChange("showLogo", false)}
                disabled={disabled}
                style={{
                  flex: 1,
                  height: "auto",
                  minHeight: "40px",
                  fontSize: "11px",
                }}
              >
                <div
                  style={{
                    textAlign: "center",
                    lineHeight: "1.1",
                    fontSize: "11px",
                  }}
                >
                  {t("certSign.noLogo", "No Logo")}
                </div>
              </Button>
              <Button
                variant={parameters.showLogo ? "filled" : "outline"}
                color={parameters.showLogo ? "blue" : "var(--text-muted)"}
                onClick={() => onParameterChange("showLogo", true)}
                disabled={disabled}
                style={{
                  flex: 1,
                  height: "auto",
                  minHeight: "40px",
                  fontSize: "11px",
                }}
              >
                <div
                  style={{
                    textAlign: "center",
                    lineHeight: "1.1",
                    fontSize: "11px",
                  }}
                >
                  {t("certSign.showLogo", "Show Logo")}
                </div>
              </Button>
            </div>
          </Stack>
        </Stack>
      )}
    </Stack>
  );
};

export default SignatureAppearanceSettings;
