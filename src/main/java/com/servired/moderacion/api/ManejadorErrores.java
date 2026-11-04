package com.servired.moderacion.api;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Convierte todo error en RFC 7807.
 *
 * <p>Anonimato: los mensajes de las excepciones pueden contener partes de la petición (la
 * {@code descripcion} es texto libre). Por eso nunca se copian a la respuesta ni al log: las
 * respuestas usan textos fijos y el log solo registra la clase de la excepción.
 */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);
    private static final String TIPO_GENERICO = "about:blank";
    private static final String TIPO_VALIDACION = "https://servired.example/problemas/validacion";
    private static final String TITULO_VALIDACION = "Solicitud inválida";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Problema.ErrorCampo> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new Problema.ErrorCampo(error.getField(), error.getDefaultMessage()))
                .toList();
        return responder(new Problema(TIPO_VALIDACION, TITULO_VALIDACION, 400,
                "Uno o más campos no son válidos.", errores));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return responder(new Problema(TIPO_VALIDACION, TITULO_VALIDACION, 400,
                "El cuerpo de la petición no es válido.", null));
    }

    /** Todo lo que resuelve Spring MVC (404, 405, 415...) sale con título fijo y sin detalle. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return responder(new Problema(TIPO_GENERICO, titulo(status), status.value(), null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> errorInesperado(Exception ex) {
        LOG.error("Error no controlado: {}", ex.getClass().getName());
        return responder(new Problema(TIPO_GENERICO, "Error interno", 500, null, null));
    }

    private static ResponseEntity<Object> responder(Problema problema) {
        return ResponseEntity.status(problema.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }

    private static String titulo(HttpStatusCode status) {
        if (status.value() == 400) {
            return TITULO_VALIDACION;
        }
        if (status.value() == 404) {
            return "No encontrado";
        }
        if (status.value() == 500) {
            return "Error interno";
        }
        HttpStatus conocido = HttpStatus.resolve(status.value());
        return conocido != null ? conocido.getReasonPhrase() : "Error";
    }
}
