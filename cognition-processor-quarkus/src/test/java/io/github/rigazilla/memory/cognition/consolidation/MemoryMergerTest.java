package io.github.rigazilla.memory.cognition.consolidation;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.chirino.memory.grpc.v1.MemoryItem;
import io.github.rigazilla.memory.cognition.extraction.MemoryCandidate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MemoryMerger refactored for Quarkus.
 */
@QuarkusTest
class MemoryMergerTest {

    @Inject
    MemoryMerger merger;

    @Test
    void merge_KeepsHigherConfidenceFromNewCandidate() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.95, List.of("E1: new citation"));

        MemoryItem existing = buildMemoryItem("key-1", 1, 0.7, List.of("old citation"));

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        assertEquals(0.95, result.candidate().confidence());
    }

    @Test
    void merge_KeepsHigherConfidenceFromExisting() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.6, List.of("E1: new citation"));

        MemoryItem existing = buildMemoryItem("key-1", 1, 0.9, List.of("old citation"));

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        assertEquals(0.9, result.candidate().confidence());
    }

    @Test
    void merge_UnionsCitations_NewFirst() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("new-cite-A", "new-cite-B"));

        MemoryItem existing = buildMemoryItem("key-1", 1, 0.8, List.of("old-cite-C"));

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        List<String> citations = result.candidate().citations();
        assertEquals(3, citations.size());
        assertEquals("new-cite-A", citations.get(0));
        assertEquals("new-cite-B", citations.get(1));
        assertEquals("old-cite-C", citations.get(2));
    }

    @Test
    void merge_DeduplicatesCitations() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("shared-cite", "new-only"));

        MemoryItem existing = buildMemoryItem("key-1", 1, 0.8, List.of("shared-cite", "old-only"));

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        List<String> citations = result.candidate().citations();
        assertEquals(3, citations.size());
        assertEquals(1, citations.stream().filter("shared-cite"::equals).count());
    }

    @Test
    void merge_CarriesExistingKeyAndRevision() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        MemoryItem existing = buildMemoryItem("my-existing-key", 7, 0.8, List.of());

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        assertTrue(result.isUpdate());
        assertEquals("my-existing-key", result.existingKey().orElseThrow());
        assertEquals(7L, result.expectedRevision().orElseThrow());
    }

    @Test
    void merge_MultipleDuplicates_PicksHighestRevision() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: citation"));

        MemoryItem older = buildMemoryItem("key-low-rev", 2, 0.8, List.of());
        MemoryItem newer = buildMemoryItem("key-high-rev", 9, 0.85, List.of());

        ResolvedCandidate result = merger.merge(newCandidate, List.of(older, newer));

        assertEquals("key-high-rev", result.existingKey().orElseThrow());
        assertEquals(9L, result.expectedRevision().orElseThrow());
    }

    @Test
    void merge_RawPrefixedCitation_StrippedBeforeUnion() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of("E1: foo", "bar"));

        MemoryItem existing = buildMemoryItem("key-1", 1, 0.8, List.of("foo", "baz"));

        ResolvedCandidate result = merger.merge(newCandidate, List.of(existing));

        List<String> citations = result.candidate().citations();
        assertEquals(1, citations.stream().filter("foo"::equals).count(),
                "raw 'E1: foo' and stored 'foo' must collapse to one entry");
        assertTrue(citations.contains("bar"));
        assertTrue(citations.contains("baz"));
        assertEquals(3, citations.size());
    }

    @Test
    void merge_EqualRevisions_DeterministicByKey() {
        MemoryCandidate newCandidate = new MemoryCandidate(
                "fact", "User prefers Python", 0.9, List.of());

        MemoryItem itemA = buildMemoryItem("key-aaa", 5, 0.8, List.of());
        MemoryItem itemB = buildMemoryItem("key-zzz", 5, 0.8, List.of());

        ResolvedCandidate result = merger.merge(newCandidate, List.of(itemA, itemB));

        ResolvedCandidate result2 = merger.merge(newCandidate, List.of(itemB, itemA));
        assertEquals(result.existingKey().orElseThrow(), result2.existingKey().orElseThrow(),
                "selection must be deterministic regardless of input list order");
    }

    @Test
    void stripEntryRefPrefix_StripsVariousFormats() {
        assertEquals("foo", MemoryMerger.stripEntryRefPrefix("E1: foo"));
        assertEquals("foo", MemoryMerger.stripEntryRefPrefix("E12: foo"));
        assertEquals("foo", MemoryMerger.stripEntryRefPrefix("E1:foo"));
        assertEquals("already clean", MemoryMerger.stripEntryRefPrefix("already clean"));
        assertEquals("Error: boom", MemoryMerger.stripEntryRefPrefix("Error: boom"));
        assertNull(MemoryMerger.stripEntryRefPrefix(null));
        assertEquals("", MemoryMerger.stripEntryRefPrefix(""));
    }

    // --- helpers ---

    private MemoryItem buildMemoryItem(String key, long revision,
            double confidence, List<String> citations) {
        ListValue.Builder citationsBuilder = ListValue.newBuilder();
        citations.forEach(c -> citationsBuilder.addValues(
                Value.newBuilder().setStringValue(c).build()));

        Struct value = Struct.newBuilder()
                .putFields("content", Value.newBuilder()
                        .setStringValue("User prefers Python").build())
                .putFields("confidence", Value.newBuilder()
                        .setNumberValue(confidence).build())
                .putFields("citations", Value.newBuilder()
                        .setListValue(citationsBuilder.build()).build())
                .build();

        return MemoryItem.newBuilder()
                .setKey(key)
                .setValue(value)
                .setRevision(revision)
                .setCreatedAt("2025-01-01T00:00:00Z")
                .build();
    }
}
