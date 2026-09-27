package com.git.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictResolutionDocumentTest {
    @Test
    void resolvesEachConflictHunkIndependentlyAndPreservesCommonLines() {
        String working = "before\n"
                + "<<<<<<< HEAD\ncurrent one\n=======\nincoming one\n>>>>>>> feature\n"
                + "between\n"
                + "<<<<<<< HEAD\ncurrent two\n=======\nincoming two\n>>>>>>> feature\n"
                + "after\n";
        ConflictResolutionDocument document = ConflictResolutionDocument.parse(
                new GitRepositoryService.ConflictContents("base", "ours", "theirs", working));

        assertEquals(2, document.hunks().size());
        assertFalse(document.hasSelections());
        assertThrows(IllegalStateException.class, document::compose);

        document.select(0, ConflictResolutionDocument.Choice.CURRENT);
        document.select(1, ConflictResolutionDocument.Choice.BOTH);

        assertEquals("before\ncurrent one\nbetween\ncurrent two\nincoming two\nafter\n",
                document.compose());
    }

    @Test
    void removesDiff3BaseSectionWhenComposingSelectedSide() {
        String working = "<<<<<<< HEAD\ncurrent\n||||||| base\ncommon ancestor\n"
                + "=======\nincoming\n>>>>>>> feature\n";
        ConflictResolutionDocument document = ConflictResolutionDocument.parse(
                new GitRepositoryService.ConflictContents("base", "ours", "theirs", working));
        document.select(0, ConflictResolutionDocument.Choice.INCOMING);

        assertEquals("incoming\n", document.compose());
    }

    @Test
    void createsOneSelectableHunkWhenConflictMarkersAreNotPresent() {
        ConflictResolutionDocument document = ConflictResolutionDocument.parse(
                new GitRepositoryService.ConflictContents(null, "current version\n",
                        "incoming version\n", "working copy\n"));
        assertEquals(1, document.hunks().size());
        document.select(0, ConflictResolutionDocument.Choice.INCOMING);

        assertTrue(document.hasSelections());
        assertEquals("incoming version\n", document.compose());
    }
}
