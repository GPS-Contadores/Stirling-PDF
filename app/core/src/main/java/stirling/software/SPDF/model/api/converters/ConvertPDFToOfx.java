package stirling.software.SPDF.model.api.converters;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.ConvertApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.util.WebResponseUtils;

/**
 * PDF bank statement to OFX.
 *
 * <p>Local experiment (GPS-Contadores): the conversion itself lives in a small Python service
 * (the "pdf-para-ofx" container), which reads the statement layout, checks that opening balance
 * plus transactions equals the closing balance, and only then writes the OFX. This controller
 * just forwards the PDF and relays the result, so the balance check stays in one place.
 */
@Slf4j
@ConvertApi
public class ConvertPDFToOfx {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // Env var PDFTOOFX_URL (Spring relaxed binding) overrides it.
    @Value("${pdfToOfx.url:http://pdf-para-ofx:8000}")
    private String serviceUrl;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/pdf/ofx",
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @Operation(
            summary = "Convert a PDF bank statement to OFX",
            description =
                    "Reads a text-based bank statement PDF and returns an OFX 2.2 file. The OFX is"
                            + " only produced if the opening balance plus the transactions matches"
                            + " the closing balance. Input:PDF Output:OFX Type:SISO")
    public ResponseEntity<byte[]> processPdfToOfx(@ModelAttribute PDFFile file) throws Exception {
        MultipartFile inputFile = file.getFileInput();
        if (inputFile == null || inputFile.isEmpty()) {
            throw new IllegalArgumentException("Nenhum PDF enviado.");
        }

        String originalName = Filenames.toSimpleFileName(inputFile.getOriginalFilename());
        String baseName =
                originalName.contains(".")
                        ? originalName.substring(0, originalName.lastIndexOf('.'))
                        : originalName;

        URI endpoint = URI.create(stripTrailingSlash(serviceUrl) + "/api/converter");
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/pdf")
                        .header(
                                "X-Filename",
                                URLEncoder.encode(originalName, StandardCharsets.UTF_8))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(inputFile.getBytes()))
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("PDF to OFX service unreachable at {}: {}", serviceUrl, e.toString());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "O serviço PDF→OFX não respondeu. O container pdf-para-ofx está no ar?");
        }

        int status = response.statusCode();
        if (status == 200) {
            return WebResponseUtils.bytesToWebResponse(
                    response.body(), baseName + ".ofx", MediaType.valueOf("application/x-ofx"));
        }

        String message = new String(response.body(), StandardCharsets.UTF_8).trim();
        if (status == 400 || status == 422) {
            // The statement itself is the problem (unknown layout, balance does not match).
            // IllegalArgumentException because JobExecutorService only lets that (and
            // BaseAppException causes) through; anything else becomes a generic 500. The
            // global handler turns it into a 400 ProblemDetail whose "detail" is the message.
            // Not 422: the frontend treats 422 as "corrupted file" and hides the reason.
            throw new IllegalArgumentException(message.isEmpty() ? "OFX não gerado." : message);
        }
        log.warn("PDF to OFX service returned HTTP {}: {}", status, message);
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "O serviço PDF→OFX falhou (HTTP " + status + ").");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
