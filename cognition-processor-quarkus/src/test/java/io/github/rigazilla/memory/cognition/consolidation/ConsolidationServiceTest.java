package io.github.rigazilla.memory.cognition.consolidation;

import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConsolidationService using QuarkusTest and CDI injection.
 */
@QuarkusTest
class ConsolidationServiceTest {

    @Inject
    ConsolidationService service;

    @InjectMock
    DuplicateDetector mockDetector;

    @InjectMock
    MemoryMerger mockMerger;

    @Test
    void deduplicate_NoDuplicates_ReturnsFreshCandidates() {
        // Given: no existing memories for any candidate
        when(mockDetector.findDuplicates(anyString(), any(MemoryCandidate.class)))
                .thenReturn(List.of());

        List<MemoryCandidate> candidates = List.of(
                new MemoryCandidate("fact", "User prefers Python", 0.9, List.of("c1")),
                new MemoryCandidate("preference", "User likes dark mode", 0.85, List.of("c2"))
        );

        // When
        List<ResolvedCandidate> result = service.deduplicate("user-1", candidates);

        // Then: all fresh inserts, merger never called
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(ResolvedCandidate::isUpdate));
        verify(mockMerger, never()).merge(any(), any());
    }

    @Test
    void deduplicate_StorageDuplicate_ReturnsMergedCandidate() {
        // Given: candidate has a duplicate in storage
        MemoryCandidate candidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("c1"));
        MemoryItem duplicate = MemoryItem.newBuilder()
                .setKey("existing-key").setRevision(2).build();
        ResolvedCandidate merged = ResolvedCandidate.merged(candidate, "existing-key", 2);

        when(mockDetector.findDuplicates("user-1", candidate)).thenReturn(List.of(duplicate));
        when(mockMerger.merge(candidate, List.of(duplicate))).thenReturn(merged);

        // When
        List<ResolvedCandidate> result = service.deduplicate("user-1", List.of(candidate));

        // Then: resolved as update
        assertEquals(1, result.size());
        assertTrue(result.get(0).isUpdate());
        assertEquals("existing-key", result.get(0).existingKey().orElseThrow());
    }

    @Test
    void deduplicate_WithinBatchDuplicates_CollapsedBeforeSearch() {
        // Given: two candidates with identical content in the same batch
        MemoryCandidate first = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("cite-A"));
        MemoryCandidate second = new MemoryCandidate(
                "fact", "User prefers Python", 0.7, List.of("cite-B"));

        when(mockDetector.findDuplicates(anyString(), any(MemoryCandidate.class)))
                .thenReturn(List.of());

        // When
        List<ResolvedCandidate> result = service.deduplicate("user-1", List.of(first, second));

        // Then: collapsed to one; detector called once not twice
        assertEquals(1, result.size());
        assertEquals(0.9, result.get(0).candidate().confidence());
        assertTrue(result.get(0).candidate().citations().containsAll(List.of("cite-A", "cite-B")));
        verify(mockDetector, times(1)).findDuplicates(anyString(), any(MemoryCandidate.class));
    }

    @Test
    void deduplicate_EmptyCandidates_ReturnsEmptyList() {
        List<ResolvedCandidate> result = service.deduplicate("user-1", List.of());

        assertTrue(result.isEmpty());
        verify(mockDetector, never()).findDuplicates(anyString(), any(MemoryCandidate.class));
    }

    @Test
    void deduplicate_WithinBatchDifferentContent_BothSurvive() {
        when(mockDetector.findDuplicates(anyString(), any(MemoryCandidate.class)))
                .thenReturn(List.of());

        List<MemoryCandidate> candidates = List.of(
                new MemoryCandidate("fact", "User prefers Python", 0.9, List.of("c1")),
                new MemoryCandidate("fact", "User likes Go", 0.85, List.of("c2"))
        );

        List<ResolvedCandidate> result = service.deduplicate("user-1", candidates);

        assertEquals(2, result.size());
        assertFalse(result.get(0).isUpdate());
        assertFalse(result.get(1).isUpdate());
    }
}