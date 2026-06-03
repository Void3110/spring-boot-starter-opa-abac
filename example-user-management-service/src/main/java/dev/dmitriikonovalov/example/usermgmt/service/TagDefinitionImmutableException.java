package dev.dmitriikonovalov.example.usermgmt.service;

/** Thrown when an update/delete targets an immutable global/system tag key. → 409 Conflict. */
public class TagDefinitionImmutableException extends RuntimeException {

    public TagDefinitionImmutableException(String key) {
        super("Global/system tag key '" + key + "' is immutable");
    }
}
