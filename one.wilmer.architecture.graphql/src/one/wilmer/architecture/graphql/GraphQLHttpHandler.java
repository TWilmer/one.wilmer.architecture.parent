package one.wilmer.architecture.graphql;

import java.io.IOException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class GraphQLHttpHandler implements HttpHandler {

    private final ArchitectureGraphQLEngine engine;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphQLHttpHandler(ArchitectureGraphQLEngine engine) {
        this.engine = engine;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS Headers (essential if you plan to use GraphiQL or a browser client)
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        // 1. Read the incoming JSON body
        try (InputStream is = exchange.getRequestBody()) {
            Map<String, Object> requestBody = mapper.readValue(is, new TypeReference<>() {});
            String query = (String) requestBody.get("query");

            
            System.out.println("Got query"+query);
            
            // 2. Execute against your Xtext architecture models
            Object result = engine.execute(query);

            // 3. Serialize and send the response
            String jsonResponse = mapper.writeValueAsString(result);
            byte[] responseBytes = jsonResponse.getBytes();
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }
}