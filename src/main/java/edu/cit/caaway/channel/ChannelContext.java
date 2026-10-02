package edu.cit.caaway.channel;

import edu.cit.caaway.config.AppInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Who we are towards Tiangge: client ID, API key and the ID of this running copy of the app. */
@Component
class ChannelContext {
    private final String instanceId;
    private final String clientId;
    private final String apiKey;

    ChannelContext(AppInstance appInstance,
                   @Value("${legacysupply.client-id}") String clientId,
                   @Value("${legacysupply.api-key}") String apiKey) {
        this.instanceId = appInstance.getId();
        this.clientId = clientId;
        this.apiKey = apiKey;
    }

    String getInstanceId() {
        return instanceId;
    }

    String getClientId() {
        return clientId;
    }

    String getApiKey() {
        return apiKey;
    }
}
