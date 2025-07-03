package com.dicom.viewer;

import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.event.Event;
import javafx.event.EventType;

public class CrosshairOverlay extends Pane {
    private Line axisX = new Line();
    private Line axisY = new Line();
    private Circle centerPoint = new Circle(6);
    private String viewType;
    private boolean isDraggingCenter = false;
    private boolean isDraggingAxisX = false;
    private boolean isDraggingAxisY = false;
    private double lastMouseX, lastMouseY;
    private Color axisXColor = Color.RED;
    private Color axisYColor = Color.LIME;
    private static final double VIEW_SIZE = 350.0;

    public CrosshairOverlay(String viewType) {
        this.viewType = viewType;
        setupAxisLine(axisX, axisXColor);
        setupAxisLine(axisY, axisYColor);
        centerPoint.setFill(Color.WHITE);
        centerPoint.setStroke(Color.BLACK);
        centerPoint.setStrokeWidth(2);
        centerPoint.setRadius(6);
        setColorsForView(viewType);
        getChildren().addAll(axisX, axisY, centerPoint);
        setPickOnBounds(false);
        setupMouseHandlers();
    }

    private void setColorsForView(String viewType) {
        switch (viewType.toLowerCase()) {
            case "axial":
                axisXColor = Color.RED;
                axisYColor = Color.BLUE;
                break;
            case "coronal":
                axisXColor = Color.BLUE;
                axisYColor = Color.LIME;
                break;
            case "sagittal":
                axisXColor = Color.RED;
                axisYColor = Color.LIME;
                break;
        }
        axisX.setStroke(axisXColor);
        axisY.setStroke(axisYColor);
    }

    private void setupAxisLine(Line line, Color color) {
        line.setStroke(color);
        line.setStrokeWidth(2);
        line.getStrokeDashArray().clear();
        line.setMouseTransparent(false);
    }

    private void setupMouseHandlers() {
        centerPoint.setOnMousePressed(e -> {
            isDraggingCenter = true;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            e.consume();
        });
        centerPoint.setOnMouseDragged(e -> {
            if (isDraggingCenter) {
                onCenterPointDragged(e.getX(), e.getY());
                e.consume();
            }
        });
        centerPoint.setOnMouseReleased(e -> {
            isDraggingCenter = false;
            e.consume();
        });
        axisX.setOnMousePressed(e -> {
            isDraggingAxisX = true;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            e.consume();
        });
        axisX.setOnMouseDragged(e -> {
            if (isDraggingAxisX) {
                onAxisXDragged(e.getY());
                e.consume();
            }
        });
        axisX.setOnMouseReleased(e -> {
            isDraggingAxisX = false;
            e.consume();
        });
        axisY.setOnMousePressed(e -> {
            isDraggingAxisY = true;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            e.consume();
        });
        axisY.setOnMouseDragged(e -> {
            if (isDraggingAxisY) {
                onAxisYDragged(e.getX());
                e.consume();
            }
        });
        axisY.setOnMouseReleased(e -> {
            isDraggingAxisY = false;
            e.consume();
        });
    }

    private void onCenterPointDragged(double x, double y) {
        if (getParent() instanceof javafx.scene.layout.StackPane) {
            javafx.scene.layout.StackPane parent = (javafx.scene.layout.StackPane) getParent();
            if (parent.getParent() instanceof javafx.scene.layout.VBox) {
                fireCrosshairMoved(x, y, "center");
            }
        }
    }

    private void onAxisXDragged(double y) {
        fireCrosshairMoved(centerPoint.getCenterX(), y, "axisX");
    }

    private void onAxisYDragged(double x) {
        fireCrosshairMoved(x, centerPoint.getCenterY(), "axisY");
    }

    private void fireCrosshairMoved(double x, double y, String dragType) {
        fireEvent(new CrosshairMoveEvent(x, y, viewType, dragType));
    }

    public void updatePosition(double x, double y) {
        axisX.setStartX(0);
        axisX.setEndX(getWidth() > 0 ? getWidth() : VIEW_SIZE);
        axisX.setStartY(y);
        axisX.setEndY(y);
        axisY.setStartX(x);
        axisY.setEndX(x);
        axisY.setStartY(0);
        axisY.setEndY(getHeight() > 0 ? getHeight() : VIEW_SIZE);
        centerPoint.setCenterX(x);
        centerPoint.setCenterY(y);
    }

    public void setAxisLinesVisible(boolean visible) {
        axisX.setVisible(visible);
        axisY.setVisible(visible);
    }

    public void setCenterPointVisible(boolean visible) {
        centerPoint.setVisible(visible);
    }

    public void setAllVisible(boolean visible) {
        setVisible(visible);
        axisX.setVisible(visible);
        axisY.setVisible(visible);
        centerPoint.setVisible(visible);
    }

    public static class CrosshairMoveEvent extends Event {
        public static final EventType<CrosshairMoveEvent> CROSSHAIR_MOVED = new EventType<>(Event.ANY, "CROSSHAIR_MOVED");
        private final double x, y;
        private final String viewType;
        private final String dragType;
        public CrosshairMoveEvent(double x, double y, String viewType, String dragType) {
            super(CROSSHAIR_MOVED);
            this.x = x;
            this.y = y;
            this.viewType = viewType;
            this.dragType = dragType;
        }
        public double getX() { return x; }
        public double getY() { return y; }
        public String getViewType() { return viewType; }
        public String getDragType() { return dragType; }
    }
} 