package stirling.software.SPDF.model.api.converters;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class ConvertXlsxToRubiRequest {

    @Schema(
            description = "The payroll spreadsheet (.xlsx) filled in by the client",
            requiredMode = Schema.RequiredMode.REQUIRED,
            format = "binary")
    private MultipartFile fileInput;

    @Schema(
            description =
                    "RUBI calculation code, used only where the spreadsheet left it blank (it is"
                            + " decided at import time, so the sheet often comes back without it)",
            example = "505")
    private String calculo;
}
