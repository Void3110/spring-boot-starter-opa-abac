package dev.dmitriikonovalov.example.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * I1 (identity half): the caller's token is read off the in-flight request, and its absence is a structured
 * failure rather than an anonymous downstream call.
 */
class CallerBearerSupplierTest {

    private final CallerBearerSupplier supplier = new CallerBearerSupplier();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bindRequestWith(String authorizationHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void readsTheBearerTokenOfTheCurrentRequest() {
        bindRequestWith("Bearer abc.def.ghi");

        assertThat(supplier.currentBearer()).isEqualTo("abc.def.ghi");
    }

    @Test // the scheme is case-insensitive per RFC 7235, the token is not touched
    void acceptsALowercaseScheme() {
        bindRequestWith("bearer abc.def.ghi");

        assertThat(supplier.currentBearer()).isEqualTo("abc.def.ghi");
    }

    @Test // fail-closed: never fall through to an anonymous downstream call
    void failsWhenTheHeaderIsAbsent() {
        bindRequestWith(null);

        assertThat(catchThrowableOfType(ToolInvocationException.class, supplier::currentBearer).code())
                .isEqualTo(CallerBearerSupplier.MISSING_BEARER_CODE);
    }

    @Test
    void failsWhenTheSchemeIsNotBearer() {
        bindRequestWith("Basic dXNlcjpwYXNz");

        assertThat(catchThrowableOfType(ToolInvocationException.class, supplier::currentBearer).code())
                .isEqualTo(CallerBearerSupplier.MISSING_BEARER_CODE);
    }

    @Test
    void failsWhenTheTokenIsBlank() {
        bindRequestWith("Bearer    ");

        assertThat(catchThrowableOfType(ToolInvocationException.class, supplier::currentBearer).code())
                .isEqualTo(CallerBearerSupplier.MISSING_BEARER_CODE);
    }

    @Test // e.g. a tool invoked outside a servlet request
    void failsWhenThereIsNoCurrentRequest() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(catchThrowableOfType(ToolInvocationException.class, supplier::currentBearer).code())
                .isEqualTo(CallerBearerSupplier.MISSING_BEARER_CODE);
    }
}
