package br.com.pertodemim.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Entrada da triagem. O texto tem no máximo 500 caracteres; lat/lon são opcionais (padrão: centro de SP). */
public record TriagemRequest(
        @NotBlank(message = "Descreva o que você precisa") @Size(max = 500, message = "O texto deve ter no máximo 500 caracteres") String texto,
        @DecimalMin(value = "-90.0", message = "latitude fora do intervalo") @DecimalMax(value = "90.0", message = "latitude fora do intervalo") Double lat,
        @DecimalMin(value = "-180.0", message = "longitude fora do intervalo") @DecimalMax(value = "180.0", message = "longitude fora do intervalo") Double lon,
        @DecimalMin(value = "0.5", message = "raioKm mínimo é 0,5") @DecimalMax(value = "200.0", message = "raioKm máximo é 200") Double raioKm,
        @NotNull(message = "Consentimento é obrigatório") @AssertTrue(message = "É necessário aceitar o termo de consentimento (LGPD)") Boolean consentimento) {
}
