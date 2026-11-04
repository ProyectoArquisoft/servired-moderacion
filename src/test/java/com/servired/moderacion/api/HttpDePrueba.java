package com.servired.moderacion.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Cliente HTTP real de las pruebas, para pasar por Tomcat y no solo por Spring MVC. */
final class HttpDePrueba {

    private final HttpClient cliente = HttpClient.newHttpClient();
    private final int puerto;

    HttpDePrueba(int puerto) {
        this.puerto = puerto;
    }

    HttpResponse<String> get(String ruta) throws IOException, InterruptedException {
        return enviar(peticion(ruta).GET());
    }

    HttpResponse<String> postJson(String ruta, String cuerpo) throws IOException, InterruptedException {
        return enviar(peticion(ruta)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)));
    }

    HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
    }

    HttpResponse<String> enviar(HttpRequest.Builder peticion) throws IOException, InterruptedException {
        return cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
    }
}
