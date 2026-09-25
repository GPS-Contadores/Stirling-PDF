import { createElement, useCallback, useMemo } from "react";
import apiClient from "@app/services/apiClient";
import { useTranslation } from "react-i18next";
import {
  ConvertParameters,
  defaultParameters,
} from "@app/hooks/tools/convert/useConvertParameters";
import { createFileFromApiResponse } from "@app/utils/fileResponseUtils";
import { alert } from "@app/components/toast";
import { normalizeAxiosErrorData } from "@app/services/errorUtils";
import i18n from "@app/i18n";
import {
  useToolOperation,
  ToolType,
  CustomProcessorResult,
} from "@app/hooks/tools/shared/useToolOperation";
import {
  getEndpointUrl,
  getEndpointName,
  isImageFormat,
  isWebFormat,
  isOfficeFormat,
} from "@app/utils/convertUtils";
import { useToolCloudStatus } from "@app/hooks/useToolCloudStatus";

// Static function that can be used by both the hook and automation executor
export const shouldProcessFilesSeparately = (
  selectedFiles: File[],
  parameters: ConvertParameters,
): boolean => {
  return (
    selectedFiles.length > 1 &&
    // Image to PDF with combineImages = false
    (((isImageFormat(parameters.fromExtension) ||
      parameters.fromExtension === "image") &&
      parameters.toExtension === "pdf" &&
      !parameters.imageOptions.combineImages) ||
      // SVG to PDF with combineIntoSinglePdf = false
      (parameters.fromExtension === "svg" &&
        parameters.toExtension === "pdf" &&
        !parameters.imageOptions.combineImages) ||
      // PDF to image conversions (each PDF should generate its own image file)
      (parameters.fromExtension === "pdf" &&
        isImageFormat(parameters.toExtension)) ||
      // PDF to PDF/A and PDF/X conversions (each PDF should be processed separately)
      (parameters.fromExtension === "pdf" &&
        (parameters.toExtension === "pdfa" ||
          parameters.toExtension === "pdfx")) ||
      // PDF to text-like/spreadsheet formats should be one output per input
      (parameters.fromExtension === "pdf" &&
        ["txt", "rtf", "csv", "xlsx", "ofx"].includes(
          parameters.toExtension,
        )) ||
      // Payroll sheet to RUBI TXT: each sheet is its own import (and may
      // already come back as a zip with one TXT per company)
      (parameters.fromExtension === "xlsx" &&
        parameters.toExtension === "rubi") ||
      // PDF to CBR conversions (each PDF should generate its own archive)
      (parameters.fromExtension === "pdf" &&
        parameters.toExtension === "cbr") ||
      // PDF to EPUB/AZW3 conversions (each PDF should generate its own ebook)
      (parameters.fromExtension === "pdf" &&
        ["epub", "azw3"].includes(parameters.toExtension)) ||
      // PDF to office format conversions (each PDF should generate its own office file)
      (parameters.fromExtension === "pdf" &&
        isOfficeFormat(parameters.toExtension)) ||
      // Office files to PDF conversions (each file should be processed separately via LibreOffice)
      (isOfficeFormat(parameters.fromExtension) &&
        parameters.toExtension === "pdf") ||
      // Web files to PDF conversions (each web file should generate its own PDF)
      ((isWebFormat(parameters.fromExtension) ||
        parameters.fromExtension === "web") &&
        parameters.toExtension === "pdf") ||
      // eBook files to PDF conversions (each file should be processed separately via Calibre)
      (["epub", "mobi", "azw3", "fb2"].includes(parameters.fromExtension) &&
        parameters.toExtension === "pdf") ||
      // Web files smart detection
      (parameters.isSmartDetection &&
        parameters.smartDetectionType === "web") ||
      // Mixed file types (smart detection)
      (parameters.isSmartDetection &&
        parameters.smartDetectionType === "mixed"))
  );
};

// Static function that can be used by both the hook and automation executor
export const buildConvertFormData = (
  parameters: ConvertParameters,
  selectedFiles: File[],
): FormData => {
  const formData = new FormData();
  const {
    fromExtension,
    toExtension,
    imageOptions,
    htmlOptions,
    emailOptions,
    pdfaOptions,
    pdfxOptions,
    cbrOptions,
    pdfToCbrOptions,
    cbzOptions,
    cbzOutputOptions,
    ebookOptions,
    epubOptions,
    rubiOptions,
  } = parameters;

  selectedFiles.forEach((file) => {
    formData.append("fileInput", file);
  });

  if (isImageFormat(toExtension)) {
    formData.append("imageFormat", toExtension);
    formData.append("colorType", imageOptions.colorType);
    formData.append("dpi", imageOptions.dpi.toString());
    formData.append("singleOrMultiple", imageOptions.singleOrMultiple);
  } else if (fromExtension === "pdf" && ["docx", "odt"].includes(toExtension)) {
    formData.append("outputFormat", toExtension);
  } else if (fromExtension === "pdf" && ["pptx", "odp"].includes(toExtension)) {
    formData.append("outputFormat", toExtension);
  } else if (fromExtension === "pdf" && ["txt", "rtf"].includes(toExtension)) {
    formData.append("outputFormat", toExtension);
  } else if (
    (isImageFormat(fromExtension) || fromExtension === "image") &&
    toExtension === "pdf"
  ) {
    formData.append("fitOption", imageOptions.fitOption);
    formData.append("colorType", imageOptions.colorType);
    formData.append("autoRotate", imageOptions.autoRotate.toString());
  } else if (fromExtension === "svg" && toExtension === "pdf") {
    formData.append(
      "combineIntoSinglePdf",
      imageOptions.combineImages.toString(),
    );
  } else if (
    (fromExtension === "html" || fromExtension === "zip") &&
    toExtension === "pdf"
  ) {
    formData.append("zoom", htmlOptions.zoomLevel.toString());
  } else if (
    (fromExtension === "eml" || fromExtension === "msg") &&
    toExtension === "pdf"
  ) {
    formData.append(
      "includeAttachments",
      emailOptions.includeAttachments.toString(),
    );
    formData.append(
      "maxAttachmentSizeMB",
      emailOptions.maxAttachmentSizeMB.toString(),
    );
    formData.append("downloadHtml", emailOptions.downloadHtml.toString());
    formData.append(
      "includeAllRecipients",
      emailOptions.includeAllRecipients.toString(),
    );
  } else if (fromExtension === "pdf" && toExtension === "pdfa") {
    formData.append("outputFormat", pdfaOptions.outputFormat);
    formData.append("strict", String(!!pdfaOptions.strict));
  } else if (fromExtension === "pdf" && toExtension === "pdfx") {
    // Use PDF/A endpoint with PDF/X format parameter
    formData.append("outputFormat", pdfxOptions?.outputFormat || "pdfx");
  } else if (fromExtension === "pdf" && toExtension === "csv") {
    formData.append("pageNumbers", "all");
  } else if (fromExtension === "pdf" && toExtension === "xlsx") {
    formData.append("pageNumbers", "all");
  } else if (fromExtension === "xlsx" && toExtension === "rubi") {
    // Left out when blank: the service then uses the code in the sheet, and
    // refuses (pointing at the cell) if neither has one.
    const calculo = rubiOptions?.calculo?.trim();
    if (calculo) formData.append("calculo", calculo);
  } else if (fromExtension === "cbr" && toExtension === "pdf") {
    formData.append("optimizeForEbook", cbrOptions.optimizeForEbook.toString());
  } else if (fromExtension === "pdf" && toExtension === "cbr") {
    formData.append("dpi", pdfToCbrOptions.dpi.toString());
  } else if (fromExtension === "cbz" && toExtension === "pdf") {
    formData.append(
      "optimizeForEbook",
      (cbzOptions?.optimizeForEbook ?? false).toString(),
    );
  } else if (fromExtension === "pdf" && toExtension === "cbz") {
    formData.append("dpi", (cbzOutputOptions?.dpi ?? 150).toString());
  } else if (
    ["epub", "mobi", "azw3", "fb2"].includes(fromExtension) &&
    toExtension === "pdf"
  ) {
    formData.append(
      "embedAllFonts",
      (ebookOptions?.embedAllFonts ?? false).toString(),
    );
    formData.append(
      "includeTableOfContents",
      (ebookOptions?.includeTableOfContents ?? false).toString(),
    );
    formData.append(
      "includePageNumbers",
      (ebookOptions?.includePageNumbers ?? false).toString(),
    );
    formData.append(
      "optimizeForEbook",
      (ebookOptions?.optimizeForEbook ?? false).toString(),
    );
  } else if (
    fromExtension === "pdf" &&
    ["epub", "azw3"].includes(toExtension)
  ) {
    formData.append(
      "detectChapters",
      (epubOptions?.detectChapters ?? true).toString(),
    );
    formData.append(
      "targetDevice",
      epubOptions?.targetDevice ?? "TABLET_PHONE_IMAGES",
    );
    formData.append(
      "outputFormat",
      epubOptions?.outputFormat ?? (toExtension === "azw3" ? "AZW3" : "EPUB"),
    );
  }

  return formData;
};

// Static function that can be used by both the hook and automation executor
export const createFileFromResponse = (
  responseData: any,
  headers: any,
  originalFileName: string,
  targetExtension: string,
): File => {
  const originalName = originalFileName.split(".")[0];

  // Map both pdfa and pdfx to pdf since they both result in PDF files
  if (targetExtension == "pdfa" || targetExtension == "pdfx") {
    targetExtension = "pdf";
  }
  // Only a fallback: the RUBI converter names the file (FP_EVENTOS_...).
  if (targetExtension == "rubi") {
    targetExtension = "txt";
  }

  const fallbackFilename = `${originalName}.${targetExtension}`;

  return createFileFromApiResponse(responseData, headers, fallbackFilename);
};

/**
 * Readable reason for a failed conversion request. Tool requests use
 * responseType "blob", so the backend's ProblemDetail (`detail`) or job error
 * (`error`) arrives as a Blob and has to be read first.
 */
export const conversionErrorMessage = async (error: any): Promise<string> => {
  try {
    const normalized = await normalizeAxiosErrorData(error?.response?.data);
    const message =
      typeof normalized === "string"
        ? normalized
        : (normalized?.detail ?? normalized?.error);
    if (typeof message === "string" && message.trim()) {
      return message.replace(/^Job failed: /, "").trim();
    }
  } catch (_e) {
    void _e;
  }
  return error?.message || "Conversion failed";
};

/**
 * Warnings a converter attached to a successful response, as base64(JSON
 * array of strings) in X-GPS-Avisos (sent by the PDF → OFX converter, e.g.
 * "bank not identified in the header"). Returns [] when there are none.
 */
export const conversionWarnings = (headers: any): string[] => {
  const raw = headers?.["x-gps-avisos"];
  if (typeof raw !== "string" || !raw) return [];
  try {
    const bytes = Uint8Array.from(atob(raw), (c) => c.charCodeAt(0));
    const parsed = JSON.parse(new TextDecoder().decode(bytes));
    return Array.isArray(parsed)
      ? parsed.filter((w): w is string => typeof w === "string" && !!w.trim())
      : [];
  } catch {
    return [];
  }
};

export interface ConversionSummary {
  entries: number;
  parser: string | null;
  /** null when the converter did not say whether the balance check passed */
  balanceOk: boolean | null;
}

/**
 * What the PDF → OFX converter says about a successful conversion: how many
 * transactions went into the OFX (X-GPS-Lancamentos), which bank layout read
 * the document (X-GPS-Parser) and whether the transactions add up to the
 * printed balance (X-GPS-Conferencia: "ok" | "divergente"). Returns null for
 * any other converter.
 */
export const conversionSummary = (headers: any): ConversionSummary | null => {
  // The RUBI converter also sends X-GPS-Lancamentos; see payrollSummary.
  if (headers?.["x-gps-arquivos"] !== undefined) return null;
  const entries = headers?.["x-gps-lancamentos"];
  if (typeof entries !== "string" || !/^\d+$/.test(entries)) return null;
  const parser = headers?.["x-gps-parser"];
  const balance = headers?.["x-gps-conferencia"];
  return {
    entries: Number(entries),
    parser: typeof parser === "string" && parser ? parser : null,
    balanceOk:
      balance === "ok" ? true : balance === "divergente" ? false : null,
  };
};

export interface PayrollSummary {
  entries: number;
  /** TXT files generated: one per company (a zip when more than one) */
  files: number;
}

/**
 * What the payroll sheet → RUBI TXT converter says about a successful
 * conversion: how many entries went into the TXT (X-GPS-Lancamentos) and how
 * many TXT files came out, one per company (X-GPS-Arquivos). Returns null for
 * any other converter.
 */
export const payrollSummary = (headers: any): PayrollSummary | null => {
  const entries = headers?.["x-gps-lancamentos"];
  const files = headers?.["x-gps-arquivos"];
  if (typeof entries !== "string" || !/^\d+$/.test(entries)) return null;
  if (typeof files !== "string" || !/^\d+$/.test(files)) return null;
  return { entries: Number(entries), files: Number(files) };
};

// Toast bodies don't preserve line breaks, so several messages go in a list.
const messageList = (items: string[]) =>
  createElement(
    "ul",
    { style: { margin: 0, paddingLeft: "1.1rem" } },
    items.map((item, i) => createElement("li", { key: i }, item)),
  );

const showConversionWarnings = (fileName: string, warnings: string[]) => {
  if (warnings.length === 0) return;
  alert({
    alertType: "warning",
    title: i18n.t("convert.warningsTitle", "{{file}}: check before importing", {
      file: fileName,
    }),
    body: messageList(warnings),
    isPersistentPopup: true,
  });
};

// One toast for the whole batch: with a dozen statements, a toast per file
// would bury the warnings shown above.
const showConversionSummaries = (
  summaries: { name: string; summary: ConversionSummary }[],
) => {
  if (summaries.length === 0) return;
  const lines = summaries.map(({ name, summary }) => {
    const parts = [
      i18n.t("convert.ofxEntries", "{{count}} transactions", {
        count: summary.entries,
      }),
    ];
    if (summary.balanceOk === true) {
      parts.push(i18n.t("convert.ofxBalanceOk", "balance check passed"));
    } else if (summary.balanceOk === false) {
      parts.push(
        i18n.t("convert.ofxBalanceMismatch", "balance check did not pass"),
      );
    }
    if (summary.parser) parts.push(summary.parser);
    return `${name}: ${parts.join(" · ")}`;
  });
  alert({
    alertType: summaries.every(({ summary }) => summary.balanceOk === true)
      ? "success"
      : "warning",
    title: i18n.t("convert.ofxSummaryTitle", "OFX ready to import"),
    body: messageList(lines),
    // The counts are the point of this toast: shown open, not behind a
    // chevron, and long enough to compare with the statement.
    expandable: false,
    durationMs: 15000,
  });
};

const showPayrollSummaries = (
  summaries: { name: string; summary: PayrollSummary }[],
) => {
  if (summaries.length === 0) return;
  const lines = summaries.map(({ name, summary }) => {
    const parts = [
      i18n.t("convert.rubiEntries", "{{count}} entries", {
        count: summary.entries,
      }),
    ];
    if (summary.files > 1) {
      parts.push(
        i18n.t("convert.rubiFiles", "{{count}} files (one per company)", {
          count: summary.files,
        }),
      );
    }
    return `${name}: ${parts.join(" · ")}`;
  });
  alert({
    alertType: "success",
    title: i18n.t("convert.rubiSummaryTitle", "RUBI TXT ready to import"),
    body: messageList(lines),
    expandable: false,
    durationMs: 15000,
  });
};

// Static processor that can be used by both the hook and automation executor
export const convertProcessor = async (
  parameters: ConvertParameters,
  selectedFiles: File[],
): Promise<CustomProcessorResult> => {
  const processedFiles: File[] = [];

  // Map PDF/X to use PDF/A endpoint
  const actualToExtension =
    parameters.toExtension === "pdfx" ? "pdfa" : parameters.toExtension;
  const endpoint = getEndpointUrl(parameters.fromExtension, actualToExtension);

  if (!endpoint) {
    throw new Error("Unsupported conversion format");
  }

  // Convert-specific routing logic: decide batch vs individual processing
  // For PDF/X, we want to treat it similar to PDF/A (separate processing)
  const isSeparateProcessing = shouldProcessFilesSeparately(selectedFiles, {
    ...parameters,
    toExtension: actualToExtension, // Use the mapped extension for decision logic
  });

  if (isSeparateProcessing) {
    // Individual processing for complex cases (PDF→image, smart detection, etc.)
    const failures: { name: string; reason: string }[] = [];
    const summaries: { name: string; summary: ConversionSummary }[] = [];
    const payrollSummaries: { name: string; summary: PayrollSummary }[] = [];
    for (const file of selectedFiles) {
      try {
        const formData = buildConvertFormData(parameters, [file]);
        const response = await apiClient.post(endpoint, formData, {
          responseType: "blob",
          // Failures here are collected and reported together below, with the
          // file name and the reason; the generic "Request error" toast would
          // only add a duplicate that names neither.
          suppressErrorToast: true,
        });

        const convertedFile = createFileFromResponse(
          response.data,
          response.headers,
          file.name,
          actualToExtension === "pdfa" ? "pdfx" : parameters.toExtension,
        );

        processedFiles.push(convertedFile);
        showConversionWarnings(file.name, conversionWarnings(response.headers));
        const summary = conversionSummary(response.headers);
        if (summary) summaries.push({ name: file.name, summary });
        const payroll = payrollSummary(response.headers);
        if (payroll)
          payrollSummaries.push({ name: file.name, summary: payroll });
      } catch (error) {
        console.warn(`Failed to convert file ${file.name}:`, error);
        failures.push({
          name: file.name,
          reason: await conversionErrorMessage(error),
        });
      }
    }

    showConversionSummaries(summaries);
    showPayrollSummaries(payrollSummaries);

    // A failed file used to vanish with only a console warning: the user got
    // the other outputs and no sign that one was missing (a bank statement
    // silently left out of a batch, for PDF → OFX). Say which and why.
    const failureList = failures.map((f) => `${f.name}: ${f.reason}`);
    if (failures.length > 0 && processedFiles.length === 0) {
      throw new Error(failureList.join(" | "));
    }
    if (failures.length > 0) {
      alert({
        alertType: "error",
        title: i18n.t(
          "convert.partialFailureTitle",
          "{{failed}} of {{total}} files were not converted",
          { failed: failures.length, total: selectedFiles.length },
        ),
        body: messageList(failureList),
        isPersistentPopup: true,
      });
    }
  } else {
    // Batch processing for simple cases (image→PDF combine)
    const formData = buildConvertFormData(parameters, selectedFiles);
    const response = await apiClient.post(endpoint, formData, {
      responseType: "blob",
    });

    const baseFilename =
      selectedFiles.length === 1 ? selectedFiles[0].name : "converted_files";

    const convertedFile = createFileFromResponse(
      response.data,
      response.headers,
      baseFilename,
      actualToExtension === "pdfa" ? "pdfx" : parameters.toExtension,
    );
    processedFiles.push(convertedFile);
    showConversionWarnings(baseFilename, conversionWarnings(response.headers));
    // A single sheet takes this path, not the per-file loop above, and one
    // sheet is the usual case: without this the RUBI summary never showed.
    const payroll = payrollSummary(response.headers);
    if (payroll) {
      showPayrollSummaries([{ name: baseFilename, summary: payroll }]);
    }
  }

  // When batch processing multiple files into one output (e.g., 3 images → 1 PDF),
  // mark all inputs as consumed even though there's only 1 output file
  const isCombiningMultiple = !isSeparateProcessing && selectedFiles.length > 1;

  return {
    files: processedFiles,
    consumedAllInputs: isCombiningMultiple,
  };
};

// Static configuration object
export const convertOperationConfig = {
  toolType: ToolType.custom,
  customProcessor: convertProcessor, // Can't use callback version here
  operationType: "convert",
  defaultParameters,
  endpoint: (params: ConvertParameters): string | undefined => {
    if (!params.fromExtension || !params.toExtension) return undefined;
    const actualToExtension =
      params.toExtension === "pdfx" ? "pdfa" : params.toExtension;
    return getEndpointUrl(params.fromExtension, actualToExtension) ?? undefined;
  },
} as const;

export const useConvertOperation = (parameters?: ConvertParameters) => {
  const { t } = useTranslation();

  // Calculate current endpoint name for cloud detection
  const currentEndpointName = useMemo(() => {
    if (!parameters?.fromExtension || !parameters?.toExtension)
      return undefined;

    // Map PDF/X to use PDF/A endpoint (same as in convertProcessor)
    const actualToExtension =
      parameters.toExtension === "pdfx" ? "pdfa" : parameters.toExtension;
    return getEndpointName(parameters.fromExtension, actualToExtension);
  }, [parameters?.fromExtension, parameters?.toExtension]);

  // Check if current conversion will use cloud
  const willUseCloud = useToolCloudStatus(currentEndpointName);

  const customConvertProcessor = useCallback(
    async (
      parameters: ConvertParameters,
      selectedFiles: File[],
    ): Promise<CustomProcessorResult> => {
      return convertProcessor(parameters, selectedFiles);
    },
    [],
  );

  const operation = useToolOperation<ConvertParameters>({
    ...convertOperationConfig,
    customProcessor: customConvertProcessor, // Use instance-specific processor for translation support
    getErrorMessage: (error) => {
      if (error.response?.data && typeof error.response.data === "string") {
        return error.response.data;
      }
      if (error.message) {
        return error.message;
      }
      return t(
        "convert.errorConversion",
        "An error occurred while converting the file.",
      );
    },
  });

  // Override willUseCloud with our calculated value
  return {
    ...operation,
    willUseCloud,
  };
};
