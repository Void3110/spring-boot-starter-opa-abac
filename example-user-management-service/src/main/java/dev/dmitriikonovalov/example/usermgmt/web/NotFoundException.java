package dev.dmitriikonovalov.example.usermgmt.web;

/** Thrown when a requested entity does not exist; mapped to 404 by {@link ApiExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
