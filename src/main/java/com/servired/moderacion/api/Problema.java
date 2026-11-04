package com.servired.moderacion.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Error RFC 7807, con la forma del esquema {@code Problema} del contrato. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problema(String type, String title, int status, String detail, List<ErrorCampo> errores) {

    public record ErrorCampo(String campo, String mensaje) {
    }
}
