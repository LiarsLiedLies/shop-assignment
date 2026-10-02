package edu.cit.caaway.config;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Identifies this running copy of the application; a new ID is generated on every start. */
@Component
public class AppInstance {

    private final String id = UUID.randomUUID().toString();

    public String getId() {
        return id;
    }
}
