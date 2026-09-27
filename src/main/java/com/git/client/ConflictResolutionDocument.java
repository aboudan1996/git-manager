package com.git.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Builds a resolved file by choosing a side independently for each merge-conflict hunk. */
final class ConflictResolutionDocument {
    enum Choice {
        CURRENT, INCOMING, BOTH
    }

    record Hunk(String current, String incoming) {
    }

    private final List<String> context;
    private final List<Hunk> hunks;
    private final List<Choice> choices;

    private ConflictResolutionDocument(List<String> context, List<Hunk> hunks) {
        this.context = List.copyOf(context);
        this.hunks = List.copyOf(hunks);
        this.choices = new ArrayList<>(Collections.nCopies(hunks.size(), null));
    }

    static ConflictResolutionDocument parse(GitRepositoryService.ConflictContents conflict) {
        String working = conflict.working() == null ? "" : conflict.working();
        String[] lines = working.split("\\R", -1);
        List<String> context = new ArrayList<>();
        List<Hunk> hunks = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        StringBuilder current = null;
        StringBuilder incoming = null;
        boolean incomingSide = false;
        boolean baseSide = false;
        boolean foundMarkers = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            boolean hasFollowingLine = index < lines.length - 1;
            if (current == null && line.startsWith("<<<<<<<")) {
                foundMarkers = true;
                context.add(plain.toString());
                plain.setLength(0);
                current = new StringBuilder();
                incoming = new StringBuilder();
                incomingSide = false;
                baseSide = false;
            } else if (current != null && line.startsWith("=======")) {
                incomingSide = true;
                baseSide = false;
            } else if (current != null && line.startsWith(">>>>>>>")) {
                hunks.add(new Hunk(current.toString(), incoming.toString()));
                current = null;
                incoming = null;
                incomingSide = false;
                baseSide = false;
            } else if (current != null && line.startsWith("|||||||")) {
                baseSide = true;
            } else if (current != null) {
                if (!baseSide) appendLine(incomingSide ? incoming : current, line, hasFollowingLine);
            } else {
                appendLine(plain, line, hasFollowingLine);
            }
        }
        if (current != null) {
            plain.append("<<<<<<< unresolved conflict\n")
                    .append(current)
                    .append("=======\n")
                    .append(incoming)
                    .append(">>>>>>> unresolved conflict\n");
        }
        if (!foundMarkers || hunks.isEmpty()) {
            return new ConflictResolutionDocument(List.of(""),
                    List.of(new Hunk(nullToEmpty(conflict.ours()),
                            nullToEmpty(conflict.theirs()))));
        }
        context.add(plain.toString());
        return new ConflictResolutionDocument(context, hunks);
    }

    List<Hunk> hunks() {
        return hunks;
    }

    void select(int index, Choice choice) {
        choices.set(index, Objects.requireNonNull(choice));
    }

    boolean hasSelections() {
        return choices.stream().allMatch(Objects::nonNull);
    }

    String compose() {
        if (!hasSelections()) {
            throw new IllegalStateException("Select a resolution for every conflict hunk first.");
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < hunks.size(); index++) {
            result.append(context.get(index));
            Hunk hunk = hunks.get(index);
            switch (choices.get(index)) {
                case CURRENT -> result.append(hunk.current());
                case INCOMING -> result.append(hunk.incoming());
                case BOTH -> result.append(hunk.current()).append(hunk.incoming());
            }
        }
        return result.append(context.get(context.size() - 1)).toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void appendLine(StringBuilder target, String line, boolean addNewline) {
        target.append(line);
        if (addNewline) target.append('\n');
    }
}
