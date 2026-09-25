import { useCallback, useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { createToolFlow } from "@app/components/tools/shared/createToolFlow";
import { useAppConfig } from "@app/contexts/AppConfigContext";
import CertificateTypeSettings from "@app/components/tools/certSign/CertificateTypeSettings";
import CertificateFormatSettings from "@app/components/tools/certSign/CertificateFormatSettings";
import CertificateFilesSettings from "@app/components/tools/certSign/CertificateFilesSettings";
import HardwareCertificateSettings from "@app/components/tools/certSign/HardwareCertificateSettings";
import SignatureAppearanceSettings from "@app/components/tools/certSign/SignatureAppearanceSettings";
import { useCertSignParameters } from "@app/hooks/tools/certSign/useCertSignParameters";
import { useCertSignOperation } from "@app/hooks/tools/certSign/useCertSignOperation";
import { useCertificateTypeTips } from "@app/components/tooltips/useCertificateTypeTips";
import { useSignatureAppearanceTips } from "@app/components/tooltips/useSignatureAppearanceTips";
import { useSignModeTips } from "@app/components/tooltips/useSignModeTips";
import { useBaseTool } from "@app/hooks/tools/shared/useBaseTool";
import { BaseToolProps, ToolComponent } from "@app/types/tool";
import { useCertSignatureArea } from "@app/contexts/CertSignatureAreaContext";
import {
  useNavigationActions,
  useNavigationState,
} from "@app/contexts/NavigationContext";
import { useSelectedFiles } from "@app/contexts/file/fileHooks";
import { useViewer } from "@app/contexts/ViewerContext";
import type { SignatureArea } from "@app/utils/signatureAreaCoordinates";

const CertSign = (props: BaseToolProps) => {
  const { t } = useTranslation();

  const base = useBaseTool(
    "certSign",
    useCertSignParameters,
    useCertSignOperation,
    props,
  );

  const { config } = useAppConfig();
  // "Upload" is always available; the source chooser is only meaningful when a
  // server certificate or a hardware token gives the user an actual alternative.
  const hasCertSourceChoice =
    (config?.serverCertificateEnabled ?? false) ||
    (config?.hardwareSigningAvailable ?? false);

  // With Upload as the only source, keep signMode on MANUAL even if a saved
  // automation set AUTO/DEVICE, so the hidden source step can't strand the flow.
  useEffect(() => {
    if (!hasCertSourceChoice && base.params.parameters.signMode !== "MANUAL") {
      base.params.updateParameter("signMode", "MANUAL");
    }
  }, [
    hasCertSourceChoice,
    base.params.parameters.signMode,
    base.params.updateParameter,
  ]);

  // The visible signature's area is drawn on the page in the main viewer.
  const { setPlacement } = useCertSignatureArea();
  const { actions: navActions } = useNavigationActions();
  const { workbench } = useNavigationState();
  const { scrollActions } = useViewer();
  const { selectedFileStubs } = useSelectedFiles();
  const { showSignature, pageNumber, signatureArea } = base.params.parameters;
  const updateParameter = base.params.updateParameter;
  const placingArea =
    showSignature && base.selectedFiles.length > 0 && !base.hasResults;

  // The same page and area go to every selected file, so the shortest one
  // bounds the page number.
  const pageCounts = selectedFileStubs
    .map((stub) => stub.processedFile?.totalPages)
    .filter((count): count is number => !!count);
  const pageCount = pageCounts.length ? Math.min(...pageCounts) : undefined;

  const handleAreaChange = useCallback(
    (pageIndex: number, area: SignatureArea) => {
      updateParameter("pageNumber", pageIndex + 1);
      updateParameter("signatureArea", area);
    },
    [updateParameter],
  );

  useEffect(() => {
    setPlacement(
      placingArea
        ? {
            pageIndex: pageNumber - 1,
            area: signatureArea,
            onChange: handleAreaChange,
            disabled: base.endpointLoading,
          }
        : null,
    );
  }, [
    placingArea,
    pageNumber,
    signatureArea,
    handleAreaChange,
    base.endpointLoading,
    setPlacement,
  ]);

  useEffect(() => () => setPlacement(null), [setPlacement]);

  // Choosing a visible signature opens the viewer, where the area is drawn.
  const wasPlacingArea = useRef(false);
  useEffect(() => {
    if (placingArea && !wasPlacingArea.current) {
      navActions.setWorkbench("viewer");
    }
    wasPlacingArea.current = placingArea;
  }, [placingArea, navActions]);

  const certTypeTips = useCertificateTypeTips();
  const appearanceTips = useSignatureAppearanceTips();
  const signModeTips = useSignModeTips();

  // Check if certificate files are configured for appearance step
  const areCertFilesConfigured = () => {
    const params = base.params.parameters;

    // Auto mode (server certificate) - always configured
    if (params.signMode === "AUTO") {
      return true;
    }

    // Manual mode - check for required files based on cert type
    switch (params.certType) {
      case "PEM":
        return !!(params.privateKeyFile && params.certFile);
      case "PKCS12":
      case "PFX":
        return !!params.p12File;
      case "JKS":
        return !!params.jksFile;
      case "WINDOWS_STORE":
        return !!params.alias;
      case "PKCS11":
        return !!(params.pkcs11LibraryPath && params.alias);
      default:
        return false;
    }
  };

  return createToolFlow({
    forceStepNumbers: true,
    files: {
      selectedFiles: base.selectedFiles,
      isCollapsed: base.hasResults,
    },
    steps: [
      {
        title: t("certSign.source.stepTitle", "Certificate source"),
        isVisible: hasCertSourceChoice,
        isCollapsed: base.settingsCollapsed,
        onCollapsedClick: base.settingsCollapsed
          ? base.handleSettingsReset
          : undefined,
        tooltip: signModeTips,
        content: (
          <CertificateTypeSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
          />
        ),
      },
      ...(base.params.parameters.signMode === "MANUAL"
        ? [
            {
              title: t("certSign.certTypeStep.stepTitle", "Certificate Format"),
              isCollapsed: base.settingsCollapsed,
              onCollapsedClick: base.settingsCollapsed
                ? base.handleSettingsReset
                : undefined,
              tooltip: certTypeTips,
              content: (
                <CertificateFormatSettings
                  parameters={base.params.parameters}
                  onParameterChange={base.params.updateParameter}
                  disabled={base.endpointLoading}
                />
              ),
            },
          ]
        : []),
      ...(base.params.parameters.signMode === "MANUAL"
        ? [
            {
              title: t("certSign.certFiles.stepTitle", "Certificate Files"),
              isCollapsed: base.settingsCollapsed,
              onCollapsedClick: base.settingsCollapsed
                ? base.handleSettingsReset
                : undefined,
              content: (
                <CertificateFilesSettings
                  parameters={base.params.parameters}
                  onParameterChange={base.params.updateParameter}
                  disabled={base.endpointLoading}
                />
              ),
            },
          ]
        : []),
      ...(base.params.parameters.signMode === "DEVICE"
        ? [
            {
              title: t("certSign.device.stepTitle", "This device"),
              isCollapsed: base.settingsCollapsed,
              onCollapsedClick: base.settingsCollapsed
                ? base.handleSettingsReset
                : undefined,
              content: (
                <HardwareCertificateSettings
                  parameters={base.params.parameters}
                  onParameterChange={base.params.updateParameter}
                  disabled={base.endpointLoading}
                />
              ),
            },
          ]
        : []),
      {
        title: t("certSign.appearance.stepTitle", "Signature Appearance"),
        isCollapsed: base.settingsCollapsed || !areCertFilesConfigured(),
        onCollapsedClick:
          base.settingsCollapsed || !areCertFilesConfigured()
            ? base.handleSettingsReset
            : undefined,
        tooltip: appearanceTips,
        content: (
          <SignatureAppearanceSettings
            parameters={base.params.parameters}
            onParameterChange={base.params.updateParameter}
            disabled={base.endpointLoading}
            pageCount={pageCount}
            areaInViewer={placingArea}
            viewerOpen={workbench === "viewer"}
            onOpenViewer={() => navActions.setWorkbench("viewer")}
            onPageChosen={(page) => scrollActions.scrollToPage(page)}
          />
        ),
      },
    ],
    executeButton: {
      text: t("certSign.sign.submit", "Sign PDF"),
      isVisible: !base.hasResults,
      loadingText: t("loading"),
      onClick: base.handleExecute,
      endpointEnabled: base.endpointEnabled,
      paramsValid: base.params.validateParameters(),
    },
    review: {
      isVisible: base.hasResults,
      operation: base.operation,
      title: t("certSign.sign.results", "Signed PDF"),
      onFileClick: base.handleThumbnailClick,
      onUndo: base.handleUndo,
    },
  });
};

// Static method to get the operation hook for automation
CertSign.tool = () => useCertSignOperation;

export default CertSign as ToolComponent;
