import { useEffect, useRef, useState } from "react";
import {
  ActionIcon,
  Box,
  Center,
  Group,
  Loader,
  Stack,
  Text,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import RestartAltIcon from "@mui/icons-material/RestartAlt";
import type { PDFDocumentProxy } from "pdfjs-dist";
import CropAreaSelector from "@app/components/tools/crop/CropAreaSelector";
import {
  calculatePDFBounds,
  PDFBounds,
  Rectangle,
} from "@app/utils/cropCoordinates";
import { pdfWorkerManager } from "@app/services/pdfWorkerManager";
import {
  SignatureArea,
  rectangleToSignatureArea,
  signatureAreaToRectangle,
} from "@app/utils/signatureAreaCoordinates";

const CONTAINER_SIZE = 250;
const NO_AREA: Rectangle = { x: 0, y: 0, width: 0, height: 0 };

interface SignatureAreaSelectorProps {
  file: File;
  /** 1-based page the signature goes on */
  pageNumber: number;
  area: SignatureArea | null;
  onAreaChange: (area: SignatureArea | null) => void;
  onPageCountChange?: (pageCount: number) => void;
  disabled?: boolean;
}

/**
 * Shows the chosen page as the reader sees it and lets the user draw the box
 * where the visible certificate signature goes.
 */
const SignatureAreaSelector = ({
  file,
  pageNumber,
  area,
  onAreaChange,
  onPageCountChange,
  disabled = false,
}: SignatureAreaSelectorProps) => {
  const { t } = useTranslation();
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null);
  const [preview, setPreview] = useState<{
    image: string;
    bounds: PDFBounds;
  } | null>(null);
  const [failed, setFailed] = useState(false);

  const onPageCountChangeRef = useRef(onPageCountChange);
  onPageCountChangeRef.current = onPageCountChange;

  useEffect(() => {
    let cancelled = false;
    let doc: PDFDocumentProxy | null = null;
    setPdf(null);
    setPreview(null);
    setFailed(false);

    (async () => {
      try {
        const buffer = await file.arrayBuffer();
        doc = await pdfWorkerManager.createDocument(buffer, {
          disableAutoFetch: true,
          disableStream: true,
        });
        if (cancelled) {
          pdfWorkerManager.destroyDocument(doc);
          return;
        }
        setPdf(doc);
        onPageCountChangeRef.current?.(doc.numPages);
      } catch (error) {
        console.error("Failed to open PDF for signature placement:", error);
        if (!cancelled) setFailed(true);
      }
    })();

    return () => {
      cancelled = true;
      if (doc) pdfWorkerManager.destroyDocument(doc);
    };
  }, [file]);

  useEffect(() => {
    if (!pdf) return;
    let cancelled = false;

    (async () => {
      try {
        const page = await pdf.getPage(
          Math.min(Math.max(1, pageNumber), pdf.numPages),
        );
        // No rotation override: pdf.js applies the page's /Rotate, so this is
        // the page as displayed, the frame the API's fractions refer to.
        const viewport = page.getViewport({ scale: 1 });
        const bounds = calculatePDFBounds(
          viewport.width,
          viewport.height,
          CONTAINER_SIZE,
          CONTAINER_SIZE,
        );
        const renderViewport = page.getViewport({
          scale: bounds.scale * (window.devicePixelRatio || 1),
        });
        const canvas = document.createElement("canvas");
        canvas.width = Math.ceil(renderViewport.width);
        canvas.height = Math.ceil(renderViewport.height);
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Canvas 2D context unavailable");
        await page.render({
          canvas,
          canvasContext: context,
          viewport: renderViewport,
        }).promise;
        if (!cancelled) {
          setPreview({ image: canvas.toDataURL("image/png"), bounds });
          setFailed(false);
        }
      } catch (error) {
        console.error("Failed to render page for signature placement:", error);
        if (!cancelled) setFailed(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [pdf, pageNumber]);

  const bounds = preview?.bounds;
  const cropArea =
    bounds && area
      ? signatureAreaToRectangle(area, bounds.actualWidth, bounds.actualHeight)
      : NO_AREA;

  return (
    <Stack gap="xs">
      <Group justify="space-between" align="center">
        <Text size="sm" fw={500}>
          {t("certSign.appearance.area.title", "Signature position")}
        </Text>
        <ActionIcon
          variant="outline"
          onClick={() => onAreaChange(null)}
          disabled={disabled || !area}
          title={t("certSign.appearance.area.reset", "Use default position")}
          aria-label={t(
            "certSign.appearance.area.reset",
            "Use default position",
          )}
        >
          <RestartAltIcon style={{ fontSize: "1rem" }} />
        </ActionIcon>
      </Group>
      <Text size="xs" c="dimmed">
        {area
          ? t(
              "certSign.appearance.area.hintChosen",
              "Drag the box to move it, or its edges to resize it.",
            )
          : t(
              "certSign.appearance.area.hint",
              "Draw a box on the page where the signature should appear. Without one, the signature goes to the default position.",
            )}
      </Text>
      <Center>
        <Box
          style={{
            width: CONTAINER_SIZE,
            height: CONTAINER_SIZE,
            border: "1px solid var(--mantine-color-gray-3)",
            borderRadius: "8px",
            backgroundColor: "var(--mantine-color-gray-0)",
            overflow: "hidden",
            position: "relative",
          }}
        >
          {preview && bounds ? (
            <CropAreaSelector
              pdfBounds={bounds}
              cropArea={cropArea}
              onCropAreaChange={(rect) =>
                onAreaChange(
                  rectangleToSignatureArea(
                    rect,
                    bounds.actualWidth,
                    bounds.actualHeight,
                  ),
                )
              }
              disabled={disabled}
            >
              <img
                src={preview.image}
                alt={t(
                  "certSign.appearance.area.previewAlt",
                  "Preview of page {{page}}",
                  { page: pageNumber },
                )}
                draggable={false}
                style={{
                  position: "absolute",
                  left: bounds.offsetX,
                  top: bounds.offsetY,
                  width: bounds.thumbnailWidth,
                  height: bounds.thumbnailHeight,
                  pointerEvents: "none",
                }}
              />
            </CropAreaSelector>
          ) : (
            <Center h="100%">
              {failed ? (
                <Text size="xs" c="dimmed">
                  {t(
                    "certSign.appearance.area.unavailable",
                    "Page preview unavailable",
                  )}
                </Text>
              ) : (
                <Loader size="sm" />
              )}
            </Center>
          )}
        </Box>
      </Center>
    </Stack>
  );
};

export default SignatureAreaSelector;
