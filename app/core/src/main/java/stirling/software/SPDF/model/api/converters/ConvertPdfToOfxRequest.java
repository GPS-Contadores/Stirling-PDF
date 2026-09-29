package stirling.software.SPDF.model.api.converters;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class ConvertPdfToOfxRequest extends PDFFile {

    @Schema(
            description =
                    "Account number as registered in Questor, for statements that do not print it"
                            + " (e.g. the Banco do Brasil statement downloaded from the website)."
                            + " Digits, dot, hyphen and the check digit X only. It overrides the"
                            + " number printed on the statement.",
            example = "27346-5")
    private String conta;
}
