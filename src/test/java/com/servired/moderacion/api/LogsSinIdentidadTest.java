package com.servired.moderacion.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.Valve;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.AbstractAccessLogValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/** Anonimato (brief §5.3): nada que identifique al cliente ni el cuerpo llegan a la salida. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class LogsSinIdentidadTest {

    private static final String TOKEN = "CANARIO-TOKEN-7f3a";
    private static final String IP = "203.0.113.77";
    private static final String AGENTE = "CANARIO-AGENTE-91bc";
    private static final String COOKIE = "CANARIO-COOKIE-44de";
    private static final String CUERPO = "CANARIO-CUERPO-e20c";
    private static final List<String> CANARIOS = List.of(TOKEN, IP, AGENTE, COOKIE, CUERPO);

    @LocalServerPort
    private int puerto;

    @Autowired
    private ServletWebServerApplicationContext contexto;

    private HttpDePrueba http;

    @BeforeEach
    void preparar() {
        http = new HttpDePrueba(puerto);
    }

    /** Sin este control, las demás pruebas pasarían aunque la captura no funcionara. */
    @Test
    void laCapturaDeSalidaVeLosLogs(CapturedOutput salida) {
        LoggerFactory.getLogger(LogsSinIdentidadTest.class).warn("sonda-de-captura");

        assertThat(salida).contains("sonda-de-captura");
    }

    @Test
    void headersDeIdentidadNoAparecenEnLaSalida(CapturedOutput salida) throws Exception {
        List<HttpResponse<String>> respuestas = new ArrayList<>();
        respuestas.add(http.enviar(conHeadersDeIdentidad("/actuator/health").GET()));
        respuestas.add(http.enviar(conHeadersDeIdentidad("/no-existe").GET()));
        respuestas.add(http.enviar(conHeadersDeIdentidad("/api/fraudes")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"descripcion\":\"" + CUERPO + "\"}"))));

        assertThat(salida).doesNotContain(CANARIOS);
        assertThat(respuestas).allSatisfy(r -> assertThat(r.body()).doesNotContain(CANARIOS));
    }

    @Test
    void jsonMalformadoNoCopiaElCuerpoNiAlLogNiALaRespuesta(CapturedOutput salida) throws Exception {
        HttpResponse<String> respuesta = http.postJson("/actuator/health", "{\"descripcion\":\"" + CUERPO);

        assertThat(salida).doesNotContain(CUERPO);
        assertThat(respuesta.body()).doesNotContain(CUERPO);
    }

    /** Tomcat rechaza esta petición antes de llegar a Spring y por defecto registra parte de ella. */
    @Test
    void peticionQueTomcatRechazaNoSeRegistra(CapturedOutput salida) throws Exception {
        String crudo = "GET /x?a=" + CUERPO + "{|} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        String respuesta;
        try (Socket socket = new Socket("localhost", puerto)) {
            OutputStream escritura = socket.getOutputStream();
            escritura.write(crudo.getBytes(StandardCharsets.ISO_8859_1));
            escritura.flush();
            respuesta = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }

        assertThat(respuesta).startsWith("HTTP/1.1 400");
        assertThat(respuesta).doesNotContain(CUERPO);
        assertThat(salida).doesNotContain(CUERPO);
    }

    /** El registro de accesos va a un archivo, no a la salida: se comprueba que la válvula no exista. */
    @Test
    void tomcatNoTieneRegistroDeAccesosNiInterpretaHeadersDeProxy() {
        Tomcat tomcat = ((TomcatWebServer) contexto.getWebServer()).getTomcat();
        List<Valve> valvulas = new ArrayList<>();
        valvulas.addAll(List.of(tomcat.getEngine().getPipeline().getValves()));
        valvulas.addAll(List.of(tomcat.getHost().getPipeline().getValves()));

        assertThat(valvulas).noneMatch(AbstractAccessLogValve.class::isInstance);
        assertThat(valvulas).noneMatch(RemoteIpValve.class::isInstance);
    }

    private HttpRequest.Builder conHeadersDeIdentidad(String ruta) {
        return http.peticion(ruta)
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Forwarded-For", IP)
                .header("User-Agent", AGENTE)
                .header("Cookie", "sesion=" + COOKIE);
    }
}
