package com.git.client.ui;

import com.git.client.git.RepositoryOperations;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class CommitGraphView {
    private static final double ROW_HEIGHT = 68;
    private static final double NODE_RADIUS = 12;
    private static final double LANE_GAP = 26;
    private static final Color[] LANE_COLORS = {
            Color.web("#69a9ff"), Color.web("#c58aff"), Color.web("#64d6b0"),
            Color.web("#f4b860"), Color.web("#ff7f9e"), Color.web("#8dd36f")
    };
    private static final DateTimeFormatter COMMIT_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm").withZone(ZoneId.systemDefault());

    private final VBox root = new VBox(10);
    private final ListView<RepositoryOperations.CommitEntry> commits = new ListView<>();
    private final TextField search = new TextField();
    private final Consumer<RepositoryOperations.CommitEntry> onSelected;
    private List<RepositoryOperations.CommitEntry> allCommits = List.of();
    private Map<String, GraphRow> graphRows = Map.of();
    private int laneCount = 1;
    private RepositoryOperations.CommitEntry pressedCommit;
    private boolean pressedCommitWasSelected;

    CommitGraphView(Consumer<RepositoryOperations.CommitEntry> onSelected) {
        this.onSelected = onSelected;
        root.getStyleClass().add("commit-graph-pane");
        search.setPromptText("Search commits...");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((observable, previous, query) -> filter(query));
        commits.setPlaceholder(new Label("No commits yet"));
        commits.getStyleClass().add("commit-graph-list");
        commits.setFixedCellSize(ROW_HEIGHT + 1);
        commits.setCellFactory(list -> new CommitCell());
        commits.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> onSelected.accept(selected));
        commits.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            pressedCommit = commitCellAt(event);
            pressedCommitWasSelected = pressedCommit != null
                    && pressedCommit.equals(commits.getSelectionModel().getSelectedItem());
        });
        commits.setOnMouseClicked(event -> {
            RepositoryOperations.CommitEntry clicked = commitCellAt(event);
            if (pressedCommitWasSelected && pressedCommit != null && pressedCommit.equals(clicked)) {
                commits.getSelectionModel().clearSelection();
            }
            pressedCommit = null;
            pressedCommitWasSelected = false;
        });
        root.getChildren().setAll(search, commits);
        VBox.setVgrow(commits, Priority.ALWAYS);
    }

    Node node() {
        return root;
    }

    void setCommits(List<RepositoryOperations.CommitEntry> entries) {
        allCommits = List.copyOf(entries);
        graphRows = layout(allCommits);
        filter(search.getText());
    }

    void clearSelection() {
        commits.getSelectionModel().clearSelection();
    }

    private void filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<RepositoryOperations.CommitEntry> filtered = allCommits.stream()
                .filter(commit -> normalized.isEmpty()
                        || commit.message().toLowerCase().contains(normalized)
                        || commit.author().toLowerCase().contains(normalized)
                        || commit.shortId().toLowerCase().contains(normalized)
                        || commit.refs().stream().anyMatch(ref -> ref.toLowerCase().contains(normalized)))
                .toList();
        commits.setItems(FXCollections.observableArrayList(filtered));
    }

    private Map<String, GraphRow> layout(List<RepositoryOperations.CommitEntry> entries) {
        List<String> activeLanes = new ArrayList<>();
        Map<String, GraphRow> rows = new HashMap<>();
        laneCount = 1;
        for (RepositoryOperations.CommitEntry commit : entries) {
            int lane = activeLanes.indexOf(commit.objectId());
            if (lane < 0) {
                lane = activeLanes.indexOf(null);
                if (lane < 0) {
                    lane = activeLanes.size();
                    activeLanes.add(commit.objectId());
                } else {
                    activeLanes.set(lane, commit.objectId());
                }
            }

            Set<Integer> lanesBefore = new HashSet<>();
            for (int index = 0; index < activeLanes.size(); index++) {
                if (activeLanes.get(index) != null) lanesBefore.add(index);
            }
            List<Integer> parentLanes = new ArrayList<>();
            for (int index = 0; index < commit.parentIds().size(); index++) {
                String parentId = commit.parentIds().get(index);
                int parentLane = activeLanes.indexOf(parentId);
                if (parentLane < 0) {
                    if (index == 0) {
                        parentLane = lane;
                        activeLanes.set(lane, parentId);
                    } else {
                        parentLane = activeLanes.indexOf(null);
                        if (parentLane < 0) {
                            parentLane = activeLanes.size();
                            activeLanes.add(parentId);
                        } else {
                            activeLanes.set(parentLane, parentId);
                        }
                    }
                }
                parentLanes.add(parentLane);
                if (index == 0 && parentLane != lane) activeLanes.set(lane, null);
            }
            if (commit.parentIds().isEmpty()) activeLanes.set(lane, null);
            laneCount = Math.max(laneCount, activeLanes.size());
            rows.put(commit.objectId(), new GraphRow(lane, Set.copyOf(lanesBefore),
                    List.copyOf(parentLanes)));
        }
        return Map.copyOf(rows);
    }

    private Color laneColor(int lane) {
        return LANE_COLORS[Math.floorMod(lane, LANE_COLORS.length)];
    }

    private RepositoryOperations.CommitEntry commitCellAt(MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && !(target instanceof ListCell<?>)) {
            target = target.getParent();
        }
        if (target instanceof ListCell<?> cell
                && cell.getListView() == commits
                && cell.getItem() instanceof RepositoryOperations.CommitEntry commit) {
            return commit;
        }
        return null;
    }

    private final class CommitCell extends ListCell<RepositoryOperations.CommitEntry> {
        @Override
        protected void updateItem(RepositoryOperations.CommitEntry commit, boolean empty) {
            super.updateItem(commit, empty);
            getStyleClass().remove("commit-graph-cell");
            if (empty || commit == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            GraphRow graphRow = graphRows.get(commit.objectId());
            String email = commit.authorEmail() == null ? "" : commit.authorEmail().trim();
            String authorDetails = commit.author() + (email.isEmpty() ? "" : " <" + email + ">");
            Pane graph = drawGraph(graphRow, authorInitials(commit.author()), authorDetails);
            VBox info = new VBox(5);
            Label summary = new Label(commit.message());
            summary.getStyleClass().add("commit-summary");
            summary.setWrapText(false);
            summary.setMaxWidth(Double.MAX_VALUE);
            Label details = new Label(commit.shortId() + "  ·  " + commit.author()
                    + "  ·  " + COMMIT_DATE.format(commit.date()));
            details.getStyleClass().add("commit-details");
            details.setWrapText(false);
            HBox metadata = new HBox(7, details);
            metadata.setAlignment(Pos.CENTER_LEFT);
            for (String ref : commit.refs()) {
                Label badge = new Label(ref);
                badge.getStyleClass().addAll("commit-ref", ref.equals("HEAD") ? "head-ref" : "branch-ref");
                metadata.getChildren().add(badge);
            }
            info.getChildren().setAll(summary, metadata);
            HBox content = new HBox(10, graph, info);
            content.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(info, Priority.ALWAYS);
            setGraphic(content);
            getStyleClass().add("commit-graph-cell");
        }
    }

    private String authorInitials(String author) {
        String[] nameParts = author == null ? new String[0] : author.trim().split("\\s+");
        if (nameParts.length == 0 || nameParts[0].isBlank()) return "??";
        String first = nameParts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        if (nameParts.length == 1) {
            String onlyName = nameParts[0];
            return onlyName.substring(0, Math.min(2, onlyName.length())).toUpperCase(Locale.ROOT);
        }
        String last = nameParts[nameParts.length - 1];
        return first + last.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private Pane drawGraph(GraphRow row, String authorInitials, String authorDetails) {
        double width = Math.max(46, 22 + laneCount * LANE_GAP);
        Canvas canvas = new Canvas(width, ROW_HEIGHT + 1);
        Pane graph = new Pane(canvas);
        graph.setMinSize(width, ROW_HEIGHT + 1);
        graph.setPrefSize(width, ROW_HEIGHT + 1);
        if (row == null) return graph;
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        double centerY = ROW_HEIGHT / 2;
        double rowEnd = ROW_HEIGHT + 1;
        for (int lane : row.activeLanes()) {
            double x = 12 + lane * LANE_GAP;
            graphics.setStroke(laneColor(lane));
            graphics.setLineWidth(2);
            if (lane == row.lane()) {
                graphics.strokeLine(x, 0, x, centerY - NODE_RADIUS);
                if (row.parentLanes().contains(lane)) {
                    graphics.strokeLine(x, centerY + NODE_RADIUS, x, rowEnd);
                }
            } else {
                graphics.strokeLine(x, 0, x, rowEnd);
            }
        }
        double nodeX = 12 + row.lane() * LANE_GAP;
        for (int parentLane : row.parentLanes()) {
            double parentX = 12 + parentLane * LANE_GAP;
            graphics.setStroke(laneColor(parentLane));
            graphics.setLineWidth(2);
            if (parentLane == row.lane()) continue;
            double direction = Math.signum(parentX - nodeX);
            graphics.beginPath();
            graphics.moveTo(nodeX, centerY);
            graphics.bezierCurveTo(
                    nodeX + direction * LANE_GAP * 0.8, centerY + 8,
                    parentX - direction * LANE_GAP * 0.8, ROW_HEIGHT - 10,
                    parentX, rowEnd);
            graphics.stroke();
        }
        graphics.setFill(laneColor(row.lane()));
        graphics.fillOval(nodeX - NODE_RADIUS, centerY - NODE_RADIUS,
                NODE_RADIUS * 2, NODE_RADIUS * 2);
        graphics.setStroke(Color.web("#d9e6f7"));
        graphics.setLineWidth(1.5);
        graphics.strokeOval(nodeX - NODE_RADIUS, centerY - NODE_RADIUS,
                NODE_RADIUS * 2, NODE_RADIUS * 2);
        graphics.setFill(Color.WHITE);
        graphics.setFont(javafx.scene.text.Font.font("Segoe UI", javafx.scene.text.FontWeight.BOLD, 9));
        graphics.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        graphics.setTextBaseline(javafx.geometry.VPos.CENTER);
        graphics.fillText(authorInitials, nodeX, centerY);
        Circle nodeTooltipTarget = new Circle(nodeX, centerY, NODE_RADIUS + 3, Color.TRANSPARENT);
        nodeTooltipTarget.setAccessibleText(authorDetails);
        Tooltip.install(nodeTooltipTarget, new Tooltip(authorDetails));
        graph.getChildren().add(nodeTooltipTarget);
        return graph;
    }

    private record GraphRow(int lane, Set<Integer> activeLanes, List<Integer> parentLanes) {
    }
}
