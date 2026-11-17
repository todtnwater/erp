package com.ssk.erp.client;

import com.ssk.erp.controller.ApiController;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Python AI 서비스(FastAPI) 호출 클라이언트 */
@Component
public class AiClient {

    private final RestClient client;
    private final RestClient pingClient;

    public AiClient(@Value("${erp.ai.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(240));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

        SimpleClientHttpRequestFactory pingFactory = new SimpleClientHttpRequestFactory();
        pingFactory.setConnectTimeout(Duration.ofSeconds(2));
        pingFactory.setReadTimeout(Duration.ofSeconds(4));
        this.pingClient = RestClient.builder().baseUrl(baseUrl).requestFactory(pingFactory).build();
    }

    public JsonNode chat(String message, String role, String actor, List<ApiController.ChatMsg> history) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("role", role);
        body.put("actor", actor);
        body.put("history", history == null ? List.of() : history);
        return client.post()
                .uri("/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Python 서비스가 꺼져 있으면 null */
    public JsonNode health() {
        try {
            return pingClient.get().uri("/health").retrieve().body(JsonNode.class);
        } catch (Exception e) {
            return null;
        }
    }

    public JsonNode reindex() {
        return client.post().uri("/index/rebuild").retrieve().body(JsonNode.class);
    }

    /** 랜덤포레스트 시장 입하량 예측 (item: 귤 / 사과 / 양파) */
    public JsonNode forecast(String item, int days) {
        return client.get()
                .uri("/forecast/{item}?days={days}", item, days)
                .retrieve()
                .body(JsonNode.class);
    }
}
