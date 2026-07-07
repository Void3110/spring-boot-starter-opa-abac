package dev.dmitriikonovalov.opaabac.security.directory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the port's defaults (U1a/U1b): the {@link NoOpUserDirectory} empty for every query, and
 * the {@link DirectoryUser} disclosure ceiling — exactly {@code subject} + {@code displayName}, nothing
 * an implementation could widen (ADR 0020 §7/§8).
 */
class NoOpUserDirectoryTest {

    private final NoOpUserDirectory directory = new NoOpUserDirectory();

    @Test
    void searchReturnsEmptyForAnyQuery() {
        assertThat(directory.search("anything", 10)).isEmpty();
        assertThat(directory.search("", 10)).isEmpty();
        assertThat(directory.search(null, 10)).isEmpty();
        assertThat(directory.search("alice", -1)).isEmpty();
    }

    @Test
    void directoryUserExposesExactlySubjectAndDisplayName() {
        // The record IS the disclosure ceiling: two components, no more (ADR 0020 §7). A third field
        // added anywhere would fail here before it could leak through an endpoint.
        assertThat(DirectoryUser.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("subject", "displayName");

        DirectoryUser user = new DirectoryUser("sub-1", "Alice");
        assertThat(user.subject()).isEqualTo("sub-1");
        assertThat(user.displayName()).isEqualTo("Alice");
    }
}
