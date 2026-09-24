package edu.cit.caaway.supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class SupplierSessionManager {

    @Value("${legacysupply.base-url}")
    private String baseUrl;

    @Value("${legacysupply.client-id}")
    private String clientId;

    @Value("${legacysupply.api-key}")
    private String apiKey;

    private String activeSessionId;

    public synchronized String getValidSessionId() {
        if (activeSessionId == null) {
            activeSessionId = login();
        }
        return activeSessionId;
    }

    public synchronized void invalidateSession() {
        this.activeSessionId = null;
    }

    public String login() {
        RestClient restClient = RestClient.create();

        String authXml = """
                <AuthRequest>
                    <ClientId>%s</ClientId>
                    <ApiKey>%s</ApiKey>
                </AuthRequest>
                """.formatted(clientId, apiKey);

        String responseXml = restClient.post()
                .uri(baseUrl + "/auth/token")
                .header("Content-Type", "application/xml")
                .body(authXml)
                .retrieve()
                .body(String.class);

        if (responseXml != null && responseXml.contains("<SessionToken>")) {
            return responseXml.substring(
                    responseXml.indexOf("<SessionToken>") + 14,
                    responseXml.indexOf("</SessionToken>")
            );
        }

        throw new IllegalStateException("Failed to parse SessionToken: " + responseXml);
    }
}