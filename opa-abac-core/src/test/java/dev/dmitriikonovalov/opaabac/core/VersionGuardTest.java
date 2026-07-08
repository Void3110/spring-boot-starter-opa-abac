package dev.dmitriikonovalov.opaabac.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link VersionGuard} (QA cases U1–U3) — the decision-to-action version binding. */
class VersionGuardTest {

    /** A resolved resource as the gate sees it: both an {@link AbacResource} and {@link Versioned}. */
    private record Resource(String type, String id, Integer version) implements AbacResource, Versioned {
        @Override
        public String abacResourceType() {
            return type;
        }

        @Override
        public String abacResourceId() {
            return id;
        }

        @Override
        public Map<String, Object> abacAttributes() {
            return Map.of();
        }

        @Override
        public Integer getVersion() {
            return version;
        }
    }

    /** A bare versioned value with no ABAC identity — exercises the class-name message fallback. */
    private record BareVersioned(Integer version) implements Versioned {
        @Override
        public Integer getVersion() {
            return version;
        }
    }

    @Test // U1 — equal versions pass
    void passesWhenVersionsMatch() {
        assertThatCode(() -> VersionGuard.requireUnchanged(
                        new Resource("category", "c-1", 3), new Resource("category", "c-1", 3)))
                .doesNotThrowAnyException();
    }

    @Test // U2 — drift throws; the message names type/id + expected/actual versions, nothing else
    void throwsOnDrift() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(
                        new Resource("category", "c-1", 3), new Resource("category", "c-1", 4)))
                .isInstanceOf(VersionConflictException.class)
                .hasMessage("Resource category/c-1 version conflict: expected 3, found 4");
    }

    @Test // U2 — drift in the other direction throws too
    void throwsOnDriftEitherDirection() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(
                        new Resource("category", "c-1", 4), new Resource("category", "c-1", 3)))
                .isInstanceOf(VersionConflictException.class)
                .hasMessage("Resource category/c-1 version conflict: expected 4, found 3");
    }

    @Test // U3 — a null snapshot version throws: an unguardable resource fails loud, never silently passes
    void throwsOnNullSnapshotVersion() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(
                        new Resource("category", "c-1", null), new Resource("category", "c-1", 3)))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("category/c-1");
    }

    @Test // U3 — a null current version throws too
    void throwsOnNullCurrentVersion() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(
                        new Resource("category", "c-1", 3), new Resource("category", "c-1", null)))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("category/c-1");
    }

    @Test // U2 — a non-AbacResource snapshot falls back to the class name in the message
    void describesBareVersionedByClassName() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(new BareVersioned(1), new BareVersioned(2)))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("BareVersioned");
    }

    @Test // guard arguments themselves are required
    void rejectsNullArguments() {
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(null, new BareVersioned(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> VersionGuard.requireUnchanged(new BareVersioned(1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
