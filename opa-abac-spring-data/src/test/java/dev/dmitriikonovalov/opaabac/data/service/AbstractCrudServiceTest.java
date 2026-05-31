package dev.dmitriikonovalov.opaabac.data.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.dmitriikonovalov.opaabac.data.model.BaseModel;
import dev.dmitriikonovalov.opaabac.data.repository.LockableJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Unit tests for the service's locking guard and mutate orchestration. No database: the real
 * pessimistic-lock serialization proof is the Testcontainers IT in ticket 4.
 */
class AbstractCrudServiceTest {

    /** A minimal model. */
    static final class Sample implements BaseModel<UUID> {
        final UUID id;
        Integer version;
        String name;

        Sample(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public Integer getVersion() {
            return version;
        }
    }

    /** A repository that is lockable (mixes both interfaces, as a real concrete repo does). */
    interface LockableSampleRepo
            extends JpaRepository<Sample, UUID>, LockableJpaRepository<Sample, UUID> {}

    static final class SampleService extends AbstractCrudService<Sample, UUID> {
        SampleService(JpaRepository<Sample, UUID> repository) {
            super(repository);
        }
    }

    @Test
    void getByIdForUpdateOnNonLockableRepoThrowsClearError() { // U8
        @SuppressWarnings("unchecked")
        JpaRepository<Sample, UUID> plainRepo = mock(JpaRepository.class);
        SampleService service = new SampleService(plainRepo);

        assertThatThrownBy(() -> service.getByIdForUpdate(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("LockableJpaRepository");
    }

    @Test
    void mutateOnNonLockableRepoThrowsBeforeAnySave() { // U8 (mutate path)
        @SuppressWarnings("unchecked")
        JpaRepository<Sample, UUID> plainRepo = mock(JpaRepository.class);
        SampleService service = new SampleService(plainRepo);

        assertThatThrownBy(() -> service.mutate(UUID.randomUUID(), s -> s.name = "x"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(plainRepo, never()).save(any());
    }

    @Test
    void getByIdForUpdateOnMissingIdThrowsNotFound() { // I8 at unit level
        LockableSampleRepo repo = mock(LockableSampleRepo.class);
        UUID id = UUID.randomUUID();
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.empty());
        SampleService service = new SampleService(repo);

        assertThatThrownBy(() -> service.getByIdForUpdate(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(id.toString())
                .hasMessageContaining("Sample"); // entity name, resolved from the generic superclass
    }

    @Test
    void mutateLocksAppliesAndSaves() {
        LockableSampleRepo repo = mock(LockableSampleRepo.class);
        UUID id = UUID.randomUUID();
        Sample existing = new Sample(id);
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        SampleService service = new SampleService(repo);

        Sample result = service.mutate(id, s -> s.name = "updated");

        assertThat(result.name).isEqualTo("updated");
        verify(repo).findByIdForUpdate(id); // locked read, not the unlocked findById
        verify(repo).save(existing);
        verify(repo, never()).findById(any());
    }

    @Test
    void getByIdOnMissingIdThrowsNotFound() {
        @SuppressWarnings("unchecked")
        JpaRepository<Sample, UUID> repo = mock(JpaRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        SampleService service = new SampleService(repo);

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
