package com.servired.moderacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ManejadorErroresTest.ControladorDePrueba.class)
@ExtendWith(OutputCaptureExtension.class)
class ManejadorErroresTest {

    private static final String SECRETO = "CANARIO-EXCEPCION-5a1f";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int puerto;

    private HttpDePrueba http;

    @BeforeEach
    void preparar() {
        http = new HttpDePrueba(puerto);
    }

    @Test
    void validacionDevuelve400ConErroresPorCampo() throws Exception {
        HttpResponse<String> respuesta = http.postJson("/prueba/validar", "{\"nombre\":\"\"}");

        JsonNode problema = problema(respuesta, 400);
        assertThat(problema.get("type").asString()).isEqualTo("https://servired.example/problemas/validacion");
        assertThat(problema.get("title").asString()).isEqualTo("Solicitud inválida");
        assertThat(problema.get("errores")).hasSize(1);
        assertThat(problema.get("errores").get(0).get("campo").asString()).isEqualTo("nombre");
        assertThat(problema.get("errores").get(0).get("mensaje").asString()).isEqualTo("es obligatorio");
    }

    @Test
    void cuerpoIlegibleDevuelve400SinCopiarElCuerpo(CapturedOutput salida) throws Exception {
        HttpResponse<String> respuesta = http.postJson("/prueba/validar", "{\"nombre\":\"" + SECRETO);

        problema(respuesta, 400);
        assertThat(respuesta.body()).doesNotContain(SECRETO);
        assertThat(salida).doesNotContain(SECRETO);
    }

    @Test
    void errorInesperadoDevuelve500SinFiltrarElMensaje(CapturedOutput salida) throws Exception {
        HttpResponse<String> respuesta = http.get("/prueba/fallar");

        JsonNode problema = problema(respuesta, 500);
        assertThat(problema.get("title").asString()).isEqualTo("Error interno");
        assertThat(respuesta.body()).doesNotContain(SECRETO);
        assertThat(salida).contains("Error no controlado: java.lang.IllegalStateException");
        assertThat(salida).doesNotContain(SECRETO);
    }

    @Test
    void rutaInexistenteDevuelve404EnRfc7807() throws Exception {
        JsonNode problema = problema(http.get("/no-existe"), 404);

        assertThat(problema.get("title").asString()).isEqualTo("No encontrado");
    }

    @Test
    void healthRespondeYSoloEseEndpointDeActuatorEstaExpuesto() throws Exception {
        HttpResponse<String> health = http.get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("UP");

        assertThat(http.get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(http.get("/actuator/httpexchanges").statusCode()).isEqualTo(404);
    }

    private static JsonNode problema(HttpResponse<String> respuesta, int status) {
        assertThat(respuesta.statusCode()).isEqualTo(status);
        assertThat(respuesta.headers().firstValue("Content-Type")).hasValueSatisfying(
                tipo -> assertThat(tipo).startsWith("application/problem+json"));
        JsonNode problema = JSON.readTree(respuesta.body());
        assertThat(problema.get("status").asInt()).isEqualTo(status);
        return problema;
    }

    record Entrada(@NotBlank(message = "es obligatorio") String nombre) {
    }

    @RestController
    static class ControladorDePrueba {

        @PostMapping("/prueba/validar")
        String validar(@Valid @RequestBody Entrada entrada) {
            return "ok";
        }

        @GetMapping("/prueba/fallar")
        String fallar() {
            throw new IllegalStateException(SECRETO);
        }
    }
}
