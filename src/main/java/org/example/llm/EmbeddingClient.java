package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls OpenAI Embeddings API (text-embedding-3-small, 1536 dims).
 * Set OPENAI_API_KEY to enable; OPENAI_EMBED_MODEL to override model.
 */
public class EmbeddingClient {

    public static final int DIMS = 1536;
    private static final String EMBED_URL   = "https://api.openai.com/v1/embeddings";
    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    public static final int BATCH_SIZE = 50;

    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private EmbeddingClient(String apiKey, String model) {
        this.apiKey  = apiKey;
        this.model   = model;
        this.http    = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper  = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers skip embedding. */
    public static EmbeddingClient create() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        String m = System.getenv("OPENAI_EMBED_MODEL");
        return new EmbeddingClient(key, (m != null && !m.isBlank()) ? m : DEFAULT_MODEL);
    }

    public String getModel() { return model; }

    /**
     * Embeds a batch of texts (max BATCH_SIZE per call).
     * Returns a list of float arrays, one per input text.
     */
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();

        Map<String, Object> body = Map.of(
            "model", model,
            "input", texts
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(EMBED_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI Embeddings HTTP " + response.statusCode()
                + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode dataArr = root.path("data");

        List<float[]> result = new ArrayList<>(texts.size());
        for (JsonNode item : dataArr) {
            JsonNode embArr = item.path("embedding");
            float[] vec = new float[embArr.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embArr.get(i).asDouble();
            }
            result.add(vec);
        }
        return result;
    }

    /** Converts a float[] vector to the pgvector string format: [v1,v2,...] */
    public static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}