package com.dicom.viewer;

import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReader;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReaderSpi;
import org.dcm4che3.io.DicomInputStream;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class ViewerUI extends Application {

    private ImageView coronalView = new ImageView();
    private ImageView sagittalView = new ImageView();
    private ImageView axialView = new ImageView();

    // Enhanced crosshair overlays for each view
    private CrosshairOverlay coronalCrosshair;
    private CrosshairOverlay sagittalCrosshair;
    private CrosshairOverlay axialCrosshair;

    // Current slice positions
    private int currentCoronalSlice = 0;
    private int currentSagittalSlice = 0;
    private int currentAxialSlice = 0;

    // Volume data - store as 3D array for MPR reconstruction
    private List<DicomSlice> dicomSlices = new ArrayList<>();
    private short[][][] volumeData; // 3D volume data [z][y][x]
    private int volumeWidth, volumeHeight, volumeDepth;

    // DICOM spatial information
    private double[] volumeOrigin = new double[3]; // First slice image position
    private double[] rowDirection = new double[3]; // Image orientation row direction
    private double[] columnDirection = new double[3]; // Image orientation column direction
    private double[] normalDirection = new double[3]; // Slice normal direction
    private double[] pixelSpacing = new double[2]; // Pixel spacing in mm
    private double sliceThickness = 1.0; // Slice thickness in mm

    // Labels for slice information
    private Label coronalLabel = new Label("Coronal View");
    private Label sagittalLabel = new Label("Sagittal View");
    private Label axialLabel = new Label("Axial View");

    // Toolbar controls
    private CheckBox crosshairTool = new CheckBox("Crosshair Tool");
    private CheckBox axisLines = new CheckBox("Axis Lines");
    private CheckBox planeIntersections = new CheckBox("Plane Intersections");
    private Slider coronalSlider = new Slider();
    private Slider sagittalSlider = new Slider();
    private Slider axialSlider = new Slider();

    // Current crosshair position (in patient coordinates - mm)
    private double[] crosshairPatientPos = new double[3];

    // Flag to prevent infinite recursion during slider updates
    private boolean isUpdatingSliders = false;
    private boolean isDragging = false;
    private String dragMode = ""; // "center", "axisX", "axisY"

    // View dimensions (fixed for consistency)
    private static final double VIEW_SIZE = 350.0;

    // Enhanced Crosshair Overlay Class
    public static class CrosshairOverlay extends Pane {
        private Line axisX = new Line(); // Horizontal line
        private Line axisY = new Line(); // Vertical line
        private Circle centerPoint = new Circle(6); // Draggable center point
        private String viewType;
        private boolean isDraggingCenter = false;
        private boolean isDraggingAxisX = false;
        private boolean isDraggingAxisY = false;
        private double lastMouseX, lastMouseY;
        
        // Colors for different axis lines based on anatomical planes
        private Color axisXColor = Color.RED;    // Coronal intersection
        private Color axisYColor = Color.LIME;   // Sagittal intersection
        
        public CrosshairOverlay(String viewType) {
            this.viewType = viewType;
            
            // Configure axis lines
            setupAxisLine(axisX, axisXColor);
            setupAxisLine(axisY, axisYColor);
            
            // Configure center point
            centerPoint.setFill(Color.WHITE);
            centerPoint.setStroke(Color.BLACK);
            centerPoint.setStrokeWidth(2);
            centerPoint.setRadius(6);
            
            // Set colors based on view type
            setColorsForView(viewType);
            
            // Add all elements to the pane
            getChildren().addAll(axisX, axisY, centerPoint);
            
            // Make the pane transparent for mouse events to pass through when not over crosshair elements
            setPickOnBounds(false);
            
            setupMouseHandlers();
        }
        
        private void setColorsForView(String viewType) {
            switch (viewType.toLowerCase()) {
                case "axial":
                    axisXColor = Color.RED;      // Coronal plane intersection (red)
                    axisYColor = Color.BLUE;     // Sagittal plane intersection (blue)
                    break;
                case "coronal":
                    axisXColor = Color.BLUE;     // Sagittal plane intersection (blue)
                    axisYColor = Color.LIME;     // Axial plane intersection (green)
                    break;
                case "sagittal":
                    axisXColor = Color.RED;      // Coronal plane intersection (red)
                    axisYColor = Color.LIME;     // Axial plane intersection (green)
                    break;
            }
            axisX.setStroke(axisXColor);
            axisY.setStroke(axisYColor);
        }
        
        private void setupAxisLine(Line line, Color color) {
            line.setStroke(color);
            line.setStrokeWidth(2);
            line.getStrokeDashArray().clear(); // Solid lines for main crosshair
            line.setMouseTransparent(false);
        }
        
        private void setupMouseHandlers() {
            // Center point mouse handlers
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
            
            // Axis X line mouse handlers
            axisX.setOnMousePressed(e -> {
                isDraggingAxisX = true;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                e.consume();
            });
            
            axisX.setOnMouseDragged(e -> {
                if (isDraggingAxisX) {
                    onAxisXDragged(e.getY()); // Y coordinate for horizontal line
                    e.consume();
                }
            });
            
            axisX.setOnMouseReleased(e -> {
                isDraggingAxisX = false;
                e.consume();
            });
            
            // Axis Y line mouse handlers
            axisY.setOnMousePressed(e -> {
                isDraggingAxisY = true;
                lastMouseX = e.getX();
                lastMouseY = e.getY();
                e.consume();
            });
            
            axisY.setOnMouseDragged(e -> {
                if (isDraggingAxisY) {
                    onAxisYDragged(e.getX()); // X coordinate for vertical line
                    e.consume();
                }
            });
            
            axisY.setOnMouseReleased(e -> {
                isDraggingAxisY = false;
                e.consume();
            });
        }
        
        private void onCenterPointDragged(double x, double y) {
            // This will be connected to the main viewer's crosshair update logic
            if (getParent() instanceof StackPane) {
                StackPane parent = (StackPane) getParent();
                if (parent.getParent() instanceof VBox) {
                    // Fire a custom event or call back to main viewer
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
            // Create a custom event to notify the main viewer
            fireEvent(new CrosshairMoveEvent(x, y, viewType, dragType));
        }
        
        public void updatePosition(double x, double y) {
            // Update axis lines
            axisX.setStartX(0);
            axisX.setEndX(getWidth() > 0 ? getWidth() : VIEW_SIZE);
            axisX.setStartY(y);
            axisX.setEndY(y);
            
            axisY.setStartX(x);
            axisY.setEndX(x);
            axisY.setStartY(0);
            axisY.setEndY(getHeight() > 0 ? getHeight() : VIEW_SIZE);
            
            // Update center point
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
    }
    
    // Custom event for crosshair movement
    public static class CrosshairMoveEvent extends javafx.event.Event {
        public static final javafx.event.EventType<CrosshairMoveEvent> CROSSHAIR_MOVED = 
            new javafx.event.EventType<>(javafx.event.Event.ANY, "CROSSHAIR_MOVED");
            
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

    // DICOM slice data structure
    public static class DicomSlice {
        public BufferedImage image;
        public short[][] pixelData; // Raw pixel values for MPR
        public double[] imagePosition = new double[3]; // (0020,0032)
        public double[] imageOrientation = new double[6]; // (0020,0037)
        public double[] pixelSpacing = new double[2]; // (0028,0030)
        public double sliceThickness = 1.0;
        public int rows, columns;
        public String instanceUID;
        public int sliceLocation;

        // Window/Level for display
        public double windowCenter = 128;
        public double windowWidth = 256;
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Enhanced DICOM Multi-Planar Reconstruction Viewer");

        // Create crosshair overlays
        coronalCrosshair = new CrosshairOverlay("coronal");
        sagittalCrosshair = new CrosshairOverlay("sagittal");
        axialCrosshair = new CrosshairOverlay("axial");

        // Setup view panes with enhanced crosshairs
        StackPane coronalPane = createEnhancedViewPane(coronalView, coronalCrosshair, "lightblue");
        StackPane sagittalPane = createEnhancedViewPane(sagittalView, sagittalCrosshair, "lightgreen");
        StackPane axialPane = createEnhancedViewPane(axialView, axialCrosshair, "lightcoral");

        // Create sliders for manual slice navigation
        setupSliders();

        // Add labels and sliders
        VBox coronalContainer = new VBox(5);
        coronalContainer.getChildren().addAll(coronalLabel, coronalPane,
                new Label("Coronal Slice:"), coronalSlider);

        VBox sagittalContainer = new VBox(5);
        sagittalContainer.getChildren().addAll(sagittalLabel, sagittalPane,
                new Label("Sagittal Slice:"), sagittalSlider);

        VBox axialContainer = new VBox(5);
        axialContainer.getChildren().addAll(axialLabel, axialPane,
                new Label("Axial Slice:"), axialSlider);

        HBox viewContainer = new HBox(10, coronalContainer, sagittalContainer, axialContainer);
        viewContainer.setSpacing(5);
        viewContainer.setPadding(new Insets(10));

        // Create enhanced toolbar
        ToolBar toolbar = createEnhancedToolbar(primaryStage);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(viewContainer);

        primaryStage.setScene(new Scene(root, 1400, 700));
        primaryStage.show();

        setupEnhancedCrosshairInteractions();
        setupToolbarActions();
    }

    private StackPane createEnhancedViewPane(ImageView imageView, CrosshairOverlay crosshair, String backgroundColor) {
        StackPane pane = new StackPane();
        pane.getChildren().addAll(imageView, crosshair);
        pane.setStyle("-fx-background-color: " + backgroundColor + ";");
        pane.setPrefSize(VIEW_SIZE, VIEW_SIZE);
        
        // Bind crosshair size to pane size
        crosshair.prefWidthProperty().bind(pane.widthProperty());
        crosshair.prefHeightProperty().bind(pane.heightProperty());
        
        return pane;
    }

    private ToolBar createEnhancedToolbar(Stage primaryStage) {
        Button loadButton = new Button("Load DICOM Folder");
        loadButton.setOnAction(e -> loadDicomVolume(primaryStage));

        Separator sep1 = new Separator();

        // Enhanced crosshair controls
        crosshairTool.setSelected(true);
        axisLines.setSelected(true);
        planeIntersections.setSelected(true);

        Button resetViewsButton = new Button("Reset Views");
        resetViewsButton.setOnAction(e -> resetToCenter());

        Button syncViewsButton = new Button("Sync Views");
        syncViewsButton.setOnAction(e -> synchronizeViews());

        // Add coordinate display
        Label coordLabel = new Label("Patient Coords: (0.0, 0.0, 0.0)");
        coordLabel.setId("coordDisplay");

        // Add slice position display
        Label sliceLabel = new Label("Slices: A:0/0 C:0/0 S:0/0");
        sliceLabel.setId("sliceDisplay");

        return new ToolBar(
                loadButton, sep1,
                new Label("Crosshair:"), crosshairTool,
                new Label("Axis Lines:"), axisLines,
                new Label("Intersections:"), planeIntersections,
                new Separator(),
                resetViewsButton, syncViewsButton,
                new Separator(),
                coordLabel,
                new Separator(),
                sliceLabel);
    }

    private void setupEnhancedCrosshairInteractions() {
        // Add event handlers for custom crosshair move events
        coronalCrosshair.addEventHandler(CrosshairMoveEvent.CROSSHAIR_MOVED, 
            e -> handleCrosshairMove(e));
        sagittalCrosshair.addEventHandler(CrosshairMoveEvent.CROSSHAIR_MOVED, 
            e -> handleCrosshairMove(e));
        axialCrosshair.addEventHandler(CrosshairMoveEvent.CROSSHAIR_MOVED, 
            e -> handleCrosshairMove(e));

        // Also keep the original click handlers for areas outside crosshair elements
        coronalView.setOnMouseClicked(e -> {
            if (coronalView.getImage() != null && crosshairTool.isSelected() && !isDragging) {
                handleCoronalClick(e.getX(), e.getY());
            }
        });

        sagittalView.setOnMouseClicked(e -> {
            if (sagittalView.getImage() != null && crosshairTool.isSelected() && !isDragging) {
                handleSagittalClick(e.getX(), e.getY());
            }
        });

        axialView.setOnMouseClicked(e -> {
            if (axialView.getImage() != null && crosshairTool.isSelected() && !isDragging) {
                handleAxialClick(e.getX(), e.getY());
            }
        });
    }

    private void handleCrosshairMove(CrosshairMoveEvent event) {
        if (isUpdatingSliders) return;
        
        String viewType = event.getViewType();
        String dragType = event.getDragType();
        double x = event.getX();
        double y = event.getY();
        
        isDragging = true;
        
        switch (viewType.toLowerCase()) {
            case "axial":
                handleAxialCrosshairMove(x, y, dragType);
                break;
            case "coronal":
                handleCoronalCrosshairMove(x, y, dragType);
                break;
            case "sagittal":
                handleSagittalCrosshairMove(x, y, dragType);
                break;
        }
        
        isDragging = false;
    }

    private void handleAxialCrosshairMove(double x, double y, String dragType) {
        if (currentAxialSlice >= dicomSlices.size()) return;
        
        DicomSlice currentSlice = dicomSlices.get(currentAxialSlice);
        
        switch (dragType) {
            case "center":
                // Update crosshair position and sync all views
                double[] patientPos = convertAxialViewToPatient(x, y, currentAxialSlice);
                updateCrosshairPosition(patientPos);
                synchronizeAllViews();
                break;
            case "axisX":
                // Drag horizontal line - changes coronal slice
                int newCoronalSlice = (int)((y / VIEW_SIZE) * volumeHeight);
                newCoronalSlice = Math.max(0, Math.min(volumeHeight - 1, newCoronalSlice));
                if (newCoronalSlice != currentCoronalSlice) {
                    coronalSlider.setValue(newCoronalSlice);
                    updateCoronalSlice(newCoronalSlice);
                    updateCrosshairFromSliceChange("coronal", newCoronalSlice);
                    updateAllCrosshairs();
                }
                break;
            case "axisY":
                // Drag vertical line - changes sagittal slice
                int newSagittalSlice = (int)((x / VIEW_SIZE) * volumeWidth);
                newSagittalSlice = Math.max(0, Math.min(volumeWidth - 1, newSagittalSlice));
                if (newSagittalSlice != currentSagittalSlice) {
                    sagittalSlider.setValue(newSagittalSlice);
                    updateSagittalSlice(newSagittalSlice);
                    updateCrosshairFromSliceChange("sagittal", newSagittalSlice);
                    updateAllCrosshairs();
                }
                break;
        }
    }

    private void handleCoronalCrosshairMove(double x, double y, String dragType) {
        switch (dragType) {
            case "center":
                double[] patientPos = convertCoronalViewToPatient(x, y, currentCoronalSlice);
                updateCrosshairPosition(patientPos);
                synchronizeAllViews();
                break;
            case "axisX":
                // Horizontal line in coronal view - changes axial slice
                int newAxialSlice = (int)((y / VIEW_SIZE) * volumeDepth);
                newAxialSlice = Math.max(0, Math.min(volumeDepth - 1, newAxialSlice));
                if (newAxialSlice != currentAxialSlice) {
                    axialSlider.setValue(newAxialSlice);
                    updateAxialSlice(newAxialSlice);
                    updateCrosshairFromSliceChange("axial", newAxialSlice);
                    updateAllCrosshairs();
                }
                break;
            case "axisY":
                // Vertical line in coronal view - changes sagittal slice
                int newSagittalSlice = (int)((x / VIEW_SIZE) * volumeWidth);
                newSagittalSlice = Math.max(0, Math.min(volumeWidth - 1, newSagittalSlice));
                if (newSagittalSlice != currentSagittalSlice) {
                    sagittalSlider.setValue(newSagittalSlice);
                    updateSagittalSlice(newSagittalSlice);
                    updateCrosshairFromSliceChange("sagittal", newSagittalSlice);
                    updateAllCrosshairs();
                }
                break;
        }
    }

    private void handleSagittalCrosshairMove(double x, double y, String dragType) {
        switch (dragType) {
            case "center":
                double[] patientPos = convertSagittalViewToPatient(x, y, currentSagittalSlice);
                updateCrosshairPosition(patientPos);
                synchronizeAllViews();
                break;
            case "axisX":
                // Horizontal line in sagittal view - changes axial slice
                int newAxialSlice = (int)((y / VIEW_SIZE) * volumeDepth);
                newAxialSlice = Math.max(0, Math.min(volumeDepth - 1, newAxialSlice));
                if (newAxialSlice != currentAxialSlice) {
                    axialSlider.setValue(newAxialSlice);
                    updateAxialSlice(newAxialSlice);
                    updateCrosshairFromSliceChange("axial", newAxialSlice);
                    updateAllCrosshairs();
                }
                break;
            case "axisY":
                // Vertical line in sagittal view - changes coronal slice
                int newCoronalSlice = (int)((x / VIEW_SIZE) * volumeHeight);
                newCoronalSlice = Math.max(0, Math.min(volumeHeight - 1, newCoronalSlice));
                if (newCoronalSlice != currentCoronalSlice) {
                    coronalSlider.setValue(newCoronalSlice);
                    updateCoronalSlice(newCoronalSlice);
                    updateCrosshairFromSliceChange("coronal", newCoronalSlice);
                    updateAllCrosshairs();
                }
                break;
        }
    }

    // Enhanced coordinate conversion methods
    private double[] convertAxialViewToPatient(double viewX, double viewY, int sliceIndex) {
        if (sliceIndex >= dicomSlices.size()) return crosshairPatientPos;
        
        DicomSlice slice = dicomSlices.get(sliceIndex);
        
        // Convert view coordinates to image pixel coordinates
        double imageX = (viewX / VIEW_SIZE) * slice.columns;
        double imageY = (viewY / VIEW_SIZE) * slice.rows;
        
        // Convert image coordinates to patient coordinates
        double[] patientPos = new double[3];
        for (int i = 0; i < 3; i++) {
            patientPos[i] = slice.imagePosition[i] +
                    (imageX * slice.pixelSpacing[0] * rowDirection[i]) +
                    (imageY * slice.pixelSpacing[1] * columnDirection[i]);
        }
        
        return patientPos;
    }

    private double[] convertCoronalViewToPatient(double viewX, double viewY, int rowIndex) {
        if (dicomSlices.isEmpty()) return crosshairPatientPos;
        
        // For coronal view: X axis = image columns, Y axis = slice depth
        double imageX = (viewX / VIEW_SIZE) * volumeWidth;
        double sliceZ = (viewY / VIEW_SIZE) * volumeDepth;
        
        DicomSlice refSlice = dicomSlices.get(0);
        
        double[] patientPos = new double[3];
        for (int i = 0; i < 3; i++) {
            patientPos[i] = refSlice.imagePosition[i] +
                    (imageX * refSlice.pixelSpacing[0] * rowDirection[i]) +
                    (rowIndex * refSlice.pixelSpacing[1] * columnDirection[i]) +
                    (sliceZ * sliceThickness * normalDirection[i]);
        }
        
        return patientPos;
    }

    private double[] convertSagittalViewToPatient(double viewX, double viewY, int columnIndex) {
        if (dicomSlices.isEmpty()) return crosshairPatientPos;
        
        // For sagittal view: X axis = image rows, Y axis = slice depth
        double imageY = (viewX / VIEW_SIZE) * volumeHeight;
        double sliceZ = (viewY / VIEW_SIZE) * volumeDepth;
        
        DicomSlice refSlice = dicomSlices.get(0);
        
        double[] patientPos = new double[3];
        for (int i = 0; i < 3; i++) {
            patientPos[i] = refSlice.imagePosition[i] +
                    (columnIndex * refSlice.pixelSpacing[0] * rowDirection[i]) +
                    (imageY * refSlice.pixelSpacing[1] * columnDirection[i]) +
                    (sliceZ * sliceThickness * normalDirection[i]);
        }
        
        return patientPos;
    }

    private void setupToolbarActions() {
        crosshairTool.setOnAction(e -> updateCrosshairVisibility());
        axisLines.setOnAction(e -> updateAxisLinesVisibility());
        planeIntersections.setOnAction(e -> updatePlaneIntersectionVisibility());
    }

    private void updateCrosshairVisibility() {
        boolean visible = crosshairTool.isSelected();
        coronalCrosshair.setAllVisible(visible);
        sagittalCrosshair.setAllVisible(visible);
        axialCrosshair.setAllVisible(visible);
    }

    private void updateAxisLinesVisibility() {
        boolean visible = axisLines.isSelected();
        coronalCrosshair.setAxisLinesVisible(visible);
        sagittalCrosshair.setAxisLinesVisible(visible);
        axialCrosshair.setAxisLinesVisible(visible);
    }

    private void updatePlaneIntersectionVisibility() {
        boolean visible = planeIntersections.isSelected();
        // This controls the center point visibility
        coronalCrosshair.setCenterPointVisible(visible);
        sagittalCrosshair.setCenterPointVisible(visible);
        axialCrosshair.setCenterPointVisible(visible);
    }

    private void updateAllCrosshairs() {
        if (!crosshairTool.isSelected()) return;
        
        updateCrosshairForAxialView();
        updateCrosshairForCoronalView();
        updateCrosshairForSagittalView();
    }

    private void updateCrosshairForAxialView() {
        if (axialView.getImage() == null || currentAxialSlice >= dicomSlices.size()) return;
        
        DicomSlice currentSlice = dicomSlices.get(currentAxialSlice);
        
        // Convert patient coordinates to image pixel coordinates
        double[] imageCoords = convertPatientToImageCoords(crosshairPatientPos, currentSlice);
        
        // Convert image coordinates to view coordinates
        double viewX = (imageCoords[0] / currentSlice.columns) * VIEW_SIZE;
        double viewY = (imageCoords[1] / currentSlice.rows) * VIEW_SIZE;
        
        axialCrosshair.updatePosition(viewX, viewY);
    }

    private void updateCrosshairForCoronalView() {
        if (coronalView.getImage() == null) return;
        
        DicomSlice refSlice = dicomSlices.get(0);
        
        // Find X position in the coronal view
        double[] toPoint = new double[3];
        for (int i = 0; i < 3; i++) {
            toPoint[i] = crosshairPatientPos[i] - refSlice.imagePosition[i];
        }
        
        double xProjection = toPoint[0] * rowDirection[0] +
                toPoint[1] * rowDirection[1] +
                toPoint[2] * rowDirection[2];
        double viewX = (xProjection / refSlice.pixelSpacing[0] / volumeWidth) * VIEW_SIZE;
        
        // Find Y position (slice depth)
        int sliceIndex = findClosestAxialSlice(crosshairPatientPos);
        double viewY = ((double) sliceIndex / volumeDepth) * VIEW_SIZE;
        
        coronalCrosshair.updatePosition(viewX, viewY);
    }

    private void updateCrosshairForSagittalView() {
        if (sagittalView.getImage() == null) return;
        
        DicomSlice refSlice = dicomSlices.get(0);
        
        // Find Y position in the sagittal view
        double[] toPoint = new double[3];
        for (int i = 0; i < 3; i++) {
            toPoint[i] = crosshairPatientPos[i] - refSlice.imagePosition[i];
        }
        
        double yProjection = toPoint[0] * columnDirection[0] +
                toPoint[1] * columnDirection[1] +
                toPoint[2] * columnDirection[2];
        double viewX = (yProjection / refSlice.pixelSpacing[1] / volumeHeight) * VIEW_SIZE;
        
        // Find Y position (slice depth)
        int sliceIndex = findClosestAxialSlice(crosshairPatientPos);
        double viewY = ((double) sliceIndex / volumeDepth) * VIEW_SIZE;
        
        sagittalCrosshair.updatePosition(viewX, viewY);
    }

    private double[] convertPatientToImageCoords(double[] patientPos, DicomSlice slice) {
        double[] imageCoords = new double[2];
        
        // Calculate the vector from slice origin to patient position
        double[] toPoint = new double[3];
        for (int i = 0; i < 3; i++) {
            toPoint[i] = patientPos[i] - slice.imagePosition[i];
        }
        
        // Project onto image axes
        double xProjection = toPoint[0] * rowDirection[0] +
                toPoint[1] * rowDirection[1] +
                toPoint[2] * rowDirection[2];
        double yProjection = toPoint[0] * columnDirection[0] +
                toPoint[1] * columnDirection[1] +
                toPoint[2] * columnDirection[2];
        
        // Convert to pixel coordinates
        imageCoords[0] = xProjection / slice.pixelSpacing[0];
        imageCoords[1] = yProjection / slice.pixelSpacing[1];
        
        return imageCoords;
    }

    private int findClosestAxialSlice(double[] patientPos) {
        if (dicomSlices.isEmpty()) return 0;
        
        double minDistance = Double.MAX_VALUE;
        int closestSlice = 0;
        
        for (int i = 0; i < dicomSlices.size(); i++) {
            DicomSlice slice = dicomSlices.get(i);
            double distance = 0;
            for (int j = 0; j < 3; j++) {
                double diff = patientPos[j] - slice.imagePosition[j];
                distance += diff * diff;
            }
            distance = Math.sqrt(distance);
            
            if (distance < minDistance) {
                minDistance = distance;
                closestSlice = i;
            }
        }
        
        return closestSlice;
    }

    private void updateCrosshairPosition(double[] newPatientPos) {
        crosshairPatientPos = newPatientPos.clone();
        updateCoordinateDisplay();
        updateAllCrosshairs();
    }

    private void updateCoordinateDisplay() {
        Label coordLabel = (Label) ((ToolBar) ((BorderPane) coronalView.getScene().getRoot()).getTop())
                .getItems().stream()
                .filter(item -> item instanceof Label && ((Label) item).getId() != null && 
                        ((Label) item).getId().equals("coordDisplay"))
                .findFirst().orElse(null);
        
        if (coordLabel != null) {
            coordLabel.setText(String.format("Patient Coords: (%.1f, %.1f, %.1f)", 
                    crosshairPatientPos[0], crosshairPatientPos[1], crosshairPatientPos[2]));
        }
    }

    private void updateSliceDisplay() {
        Label sliceLabel = (Label) ((ToolBar) ((BorderPane) coronalView.getScene().getRoot()).getTop())
                .getItems().stream()
                .filter(item -> item instanceof Label && ((Label) item).getId() != null && 
                        ((Label) item).getId().equals("sliceDisplay"))
                .findFirst().orElse(null);
        
        if (sliceLabel != null) {
            sliceLabel.setText(String.format("Slices: A:%d/%d C:%d/%d S:%d/%d", 
                    currentAxialSlice + 1, volumeDepth,
                    currentCoronalSlice + 1, volumeHeight,
                    currentSagittalSlice + 1, volumeWidth));
        }
    }

    private void updateCrosshairFromSliceChange(String viewType, int newSlicePosition) {
        if (dicomSlices.isEmpty()) return;
        
        DicomSlice refSlice = dicomSlices.get(0);
        double[] newPatientPos = crosshairPatientPos.clone();
        
        switch (viewType.toLowerCase()) {
            case "axial":
                if (newSlicePosition < dicomSlices.size()) {
                    DicomSlice newSlice = dicomSlices.get(newSlicePosition);
                    newPatientPos = newSlice.imagePosition.clone();
                }
                break;
            case "coronal":
                for (int i = 0; i < 3; i++) {
                    newPatientPos[i] = refSlice.imagePosition[i] +
                            (newSlicePosition * refSlice.pixelSpacing[1] * columnDirection[i]);
                }
                break;
            case "sagittal":
                for (int i = 0; i < 3; i++) {
                    newPatientPos[i] = refSlice.imagePosition[i] +
                            (newSlicePosition * refSlice.pixelSpacing[0] * rowDirection[i]);
                }
                break;
        }
        
        updateCrosshairPosition(newPatientPos);
    }

    private void synchronizeAllViews() {
        if (isUpdatingSliders) return;
        
        isUpdatingSliders = true;
        
        // Update slice positions based on crosshair position
        int newAxialSlice = findClosestAxialSlice(crosshairPatientPos);
        int newCoronalSlice = calculateCoronalSliceFromPatientPos(crosshairPatientPos);
        int newSagittalSlice = calculateSagittalSliceFromPatientPos(crosshairPatientPos);
        
        // Update sliders and views
        if (newAxialSlice != currentAxialSlice) {
            axialSlider.setValue(newAxialSlice);
            updateAxialSlice(newAxialSlice);
        }
        
        if (newCoronalSlice != currentCoronalSlice) {
            coronalSlider.setValue(newCoronalSlice);
            updateCoronalSlice(newCoronalSlice);
        }
        
        if (newSagittalSlice != currentSagittalSlice) {
            sagittalSlider.setValue(newSagittalSlice);
            updateSagittalSlice(newSagittalSlice);
        }
        
        updateSliceDisplay();
        isUpdatingSliders = false;
    }

    private int calculateCoronalSliceFromPatientPos(double[] patientPos) {
        if (dicomSlices.isEmpty()) return 0;
        
        DicomSlice refSlice = dicomSlices.get(0);
        double[] toPoint = new double[3];
        for (int i = 0; i < 3; i++) {
            toPoint[i] = patientPos[i] - refSlice.imagePosition[i];
        }
        
        double yProjection = toPoint[0] * columnDirection[0] +
                toPoint[1] * columnDirection[1] +
                toPoint[2] * columnDirection[2];
        
        int sliceIndex = (int) (yProjection / refSlice.pixelSpacing[1]);
        return Math.max(0, Math.min(volumeHeight - 1, sliceIndex));
    }

    private int calculateSagittalSliceFromPatientPos(double[] patientPos) {
        if (dicomSlices.isEmpty()) return 0;
        
        DicomSlice refSlice = dicomSlices.get(0);
        double[] toPoint = new double[3];
        for (int i = 0; i < 3; i++) {
            toPoint[i] = patientPos[i] - refSlice.imagePosition[i];
        }
        
        double xProjection = toPoint[0] * rowDirection[0] +
                toPoint[1] * rowDirection[1] +
                toPoint[2] * rowDirection[2];
        
        int sliceIndex = (int) (xProjection / refSlice.pixelSpacing[0]);
        return Math.max(0, Math.min(volumeWidth - 1, sliceIndex));
    }

    private void setupSliders() {
        // Coronal slider
        coronalSlider.setMin(0);
        coronalSlider.setMax(100);
        coronalSlider.setValue(50);
        coronalSlider.setShowTickLabels(true);
        coronalSlider.setShowTickMarks(true);
        coronalSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!isUpdatingSliders) {
                updateCoronalSlice(newVal.intValue());
                updateCrosshairFromSliceChange("coronal", newVal.intValue());
                updateAllCrosshairs();
            }
        });

        // Sagittal slider
        sagittalSlider.setMin(0);
        sagittalSlider.setMax(100);
        sagittalSlider.setValue(50);
        sagittalSlider.setShowTickLabels(true);
        sagittalSlider.setShowTickMarks(true);
        sagittalSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!isUpdatingSliders) {
                updateSagittalSlice(newVal.intValue());
                updateCrosshairFromSliceChange("sagittal", newVal.intValue());
                updateAllCrosshairs();
            }
        });

        // Axial slider
        axialSlider.setMin(0);
        axialSlider.setMax(100);
        axialSlider.setValue(50);
        axialSlider.setShowTickLabels(true);
        axialSlider.setShowTickMarks(true);
        axialSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!isUpdatingSliders) {
                updateAxialSlice(newVal.intValue());
                updateCrosshairFromSliceChange("axial", newVal.intValue());
                updateAllCrosshairs();
            }
        });
    }

    // Original click handlers for backward compatibility
    private void handleAxialClick(double x, double y) {
        double[] patientPos = convertAxialViewToPatient(x, y, currentAxialSlice);
        updateCrosshairPosition(patientPos);
        synchronizeAllViews();
    }

    private void handleCoronalClick(double x, double y) {
        double[] patientPos = convertCoronalViewToPatient(x, y, currentCoronalSlice);
        updateCrosshairPosition(patientPos);
        synchronizeAllViews();
    }

    private void handleSagittalClick(double x, double y) {
        double[] patientPos = convertSagittalViewToPatient(x, y, currentSagittalSlice);
        updateCrosshairPosition(patientPos);
        synchronizeAllViews();
    }

    private void resetToCenter() {
        if (dicomSlices.isEmpty()) return;
        
        // Reset to center of volume
        currentAxialSlice = volumeDepth / 2;
        currentCoronalSlice = volumeHeight / 2;
        currentSagittalSlice = volumeWidth / 2;
        
        // Update sliders
        isUpdatingSliders = true;
        axialSlider.setValue(currentAxialSlice);
        coronalSlider.setValue(currentCoronalSlice);
        sagittalSlider.setValue(currentSagittalSlice);
        isUpdatingSliders = false;
        
        // Update views
        updateAxialSlice(currentAxialSlice);
        updateCoronalSlice(currentCoronalSlice);
        updateSagittalSlice(currentSagittalSlice);
        
        // Update crosshair position
        DicomSlice centerSlice = dicomSlices.get(currentAxialSlice);
        crosshairPatientPos = centerSlice.imagePosition.clone();
        
        updateAllCrosshairs();
        updateCoordinateDisplay();
        updateSliceDisplay();
    }

    private void synchronizeViews() {
        // Force synchronization of all views based on current crosshair position
        synchronizeAllViews();
    }

    private void updateAxialSlice(int sliceIndex) {
        if (sliceIndex < 0 || sliceIndex >= dicomSlices.size()) return;
        
        currentAxialSlice = sliceIndex;
        DicomSlice slice = dicomSlices.get(sliceIndex);
        
        if (slice.image != null) {
            WritableImage fxImage = SwingFXUtils.toFXImage(slice.image, null);
            axialView.setImage(fxImage);
            axialView.setFitWidth(VIEW_SIZE);
            axialView.setFitHeight(VIEW_SIZE);
            axialView.setPreserveRatio(true);
        }
        
        updateSliceDisplay();
    }

    private void updateCoronalSlice(int rowIndex) {
        if (volumeData == null || rowIndex < 0 || rowIndex >= volumeHeight) return;
        
        currentCoronalSlice = rowIndex;
        BufferedImage coronalImage = generateCoronalSlice(rowIndex);
        
        if (coronalImage != null) {
            WritableImage fxImage = SwingFXUtils.toFXImage(coronalImage, null);
            coronalView.setImage(fxImage);
            coronalView.setFitWidth(VIEW_SIZE);
            coronalView.setFitHeight(VIEW_SIZE);
            coronalView.setPreserveRatio(true);
        }
        
        updateSliceDisplay();
    }

    private void updateSagittalSlice(int columnIndex) {
        if (volumeData == null || columnIndex < 0 || columnIndex >= volumeWidth) return;
        
        currentSagittalSlice = columnIndex;
        BufferedImage sagittalImage = generateSagittalSlice(columnIndex);
        
        if (sagittalImage != null) {
            WritableImage fxImage = SwingFXUtils.toFXImage(sagittalImage, null);
            sagittalView.setImage(fxImage);
            sagittalView.setFitWidth(VIEW_SIZE);
            sagittalView.setFitHeight(VIEW_SIZE);
            sagittalView.setPreserveRatio(true);
        }
        
        updateSliceDisplay();
    }

    private BufferedImage generateCoronalSlice(int rowIndex) {
        if (volumeData == null) return null;
        
        BufferedImage coronalImage = new BufferedImage(volumeWidth, volumeDepth, BufferedImage.TYPE_BYTE_GRAY);
        
        // Use window center/width from the first slice
        DicomSlice refSlice = dicomSlices.get(0);
        double wc = refSlice.windowCenter;
        double ww = refSlice.windowWidth;
        
        for (int z = 0; z < volumeDepth; z++) {
            for (int x = 0; x < volumeWidth; x++) {
                if (z < volumeData.length && rowIndex < volumeData[z].length && x < volumeData[z][rowIndex].length) {
                    short pixelValue = volumeData[z][rowIndex][x];
                    // Windowing formula
                    int grayValue = (int) (((pixelValue - (wc - 0.5)) / (ww - 1) + 0.5) * 255.0);
                    grayValue = Math.max(0, Math.min(255, grayValue));
                    int rgb = (grayValue << 16) | (grayValue << 8) | grayValue;
                    coronalImage.setRGB(x, volumeDepth - 1 - z, rgb);
                }
            }
        }
        
        return coronalImage;
    }

    private BufferedImage generateSagittalSlice(int columnIndex) {
        if (volumeData == null) return null;
        
        BufferedImage sagittalImage = new BufferedImage(volumeHeight, volumeDepth, BufferedImage.TYPE_BYTE_GRAY);
        
        // Use window center/width from the first slice
        DicomSlice refSlice = dicomSlices.get(0);
        double wc = refSlice.windowCenter;
        double ww = refSlice.windowWidth;
        
        for (int z = 0; z < volumeDepth; z++) {
            for (int y = 0; y < volumeHeight; y++) {
                if (z < volumeData.length && y < volumeData[z].length && columnIndex < volumeData[z][y].length) {
                    short pixelValue = volumeData[z][y][columnIndex];
                    // Windowing formula
                    int grayValue = (int) (((pixelValue - (wc - 0.5)) / (ww - 1) + 0.5) * 255.0);
                    grayValue = Math.max(0, Math.min(255, grayValue));
                    int rgb = (grayValue << 16) | (grayValue << 8) | grayValue;
                    sagittalImage.setRGB(y, volumeDepth - 1 - z, rgb);
                }
            }
        }
        
        return sagittalImage;
    }

    private void loadDicomVolume(Stage primaryStage) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select DICOM Folder");
        File selectedDirectory = directoryChooser.showDialog(primaryStage);
        
        if (selectedDirectory != null) {
            try {
                loadDicomSlicesFromDirectory(selectedDirectory);
                if (!dicomSlices.isEmpty()) {
                    buildVolumeData();
                    setupSlidersForLoadedVolume();
                    resetToCenter();
                    
                    // Show success message
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("DICOM Loading");
                    alert.setHeaderText("Volume Loaded Successfully");
                    alert.setContentText(String.format("Loaded %d slices\nVolume dimensions: %dx%dx%d", 
                            dicomSlices.size(), volumeWidth, volumeHeight, volumeDepth));
                    alert.showAndWait();
                }
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("Failed to load DICOM volume");
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            }
        }
    }

    private void loadDicomSlicesFromDirectory(File directory) throws IOException {
        dicomSlices.clear();
        File[] files = directory.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".dcm") || 
                name.toLowerCase().endsWith(".dicom") ||
                !name.contains("."));
        
        if (files == null || files.length == 0) {
            throw new IOException("No DICOM files found in the selected directory");
        }
        
        Arrays.sort(files);
        
        for (File file : files) {
            try {
                DicomSlice slice = loadDicomSlice(file);
                if (slice != null) {
                    dicomSlices.add(slice);
                }
            } catch (Exception e) {
                System.err.println("Failed to load DICOM file: " + file.getName() + " - " + e.getMessage());
            }
        }
        
        if (dicomSlices.isEmpty()) {
            throw new IOException("No valid DICOM slices could be loaded");
        }
        
        // Sort slices by position
        dicomSlices.sort((a, b) -> Double.compare(a.sliceLocation, b.sliceLocation));
        
        // Extract volume information from first slice
        DicomSlice firstSlice = dicomSlices.get(0);
        volumeWidth = firstSlice.columns;
        volumeHeight = firstSlice.rows;
        volumeDepth = dicomSlices.size();
        
        // Set spatial information
        volumeOrigin = firstSlice.imagePosition.clone();
        System.arraycopy(firstSlice.imageOrientation, 0, rowDirection, 0, 3);
        System.arraycopy(firstSlice.imageOrientation, 3, columnDirection, 0, 3);
        
        // Calculate normal direction (cross product of row and column directions)
        normalDirection[0] = rowDirection[1] * columnDirection[2] - rowDirection[2] * columnDirection[1];
        normalDirection[1] = rowDirection[2] * columnDirection[0] - rowDirection[0] * columnDirection[2];
        normalDirection[2] = rowDirection[0] * columnDirection[1] - rowDirection[1] * columnDirection[0];
        
        pixelSpacing = firstSlice.pixelSpacing.clone();
        sliceThickness = firstSlice.sliceThickness;
    }

    private DicomSlice loadDicomSlice(File file) throws IOException {
        DicomSlice slice = new DicomSlice();
        
        try (DicomInputStream dis = new DicomInputStream(file)) {
            Attributes attributes = dis.readDataset(-1, -1);
            
            // Extract basic information
            slice.rows = attributes.getInt(Tag.Rows, 0);
            slice.columns = attributes.getInt(Tag.Columns, 0);
            slice.instanceUID = attributes.getString(Tag.SOPInstanceUID, "");
            
            // Extract spatial information
            double[] imagePosition = attributes.getDoubles(Tag.ImagePositionPatient);
            if (imagePosition != null && imagePosition.length >= 3) {
                slice.imagePosition = imagePosition;
                slice.sliceLocation =(int) imagePosition[2]; // Z coordinate for sorting
            }
            
            double[] imageOrientation = attributes.getDoubles(Tag.ImageOrientationPatient);
            if (imageOrientation != null && imageOrientation.length >= 6) {
                slice.imageOrientation = imageOrientation;
            }
            
            double[] pixelSpacing = attributes.getDoubles(Tag.PixelSpacing);
            if (pixelSpacing != null && pixelSpacing.length >= 2) {
                slice.pixelSpacing = pixelSpacing;
            }
            
            slice.sliceThickness = attributes.getDouble(Tag.SliceThickness, 1.0);
            
            // Extract window/level information
            slice.windowCenter = attributes.getDouble(Tag.WindowCenter, 128);
            slice.windowWidth = attributes.getDouble(Tag.WindowWidth, 256);
            
            // Load image data
            ImageInputStream iis = ImageIO.createImageInputStream(file);
            DicomImageReader reader = new DicomImageReader(new DicomImageReaderSpi());
            reader.setInput(iis);
            
            BufferedImage img = reader.read(0);
            slice.image = img;
            slice.pixelData = new short[slice.rows][slice.columns];

            // Extract pixel data from the raster (works for 8-bit and 16-bit images)
            java.awt.image.Raster raster = img.getRaster();
            for (int y = 0; y < slice.rows; y++) {
                for (int x = 0; x < slice.columns; x++) {
                    slice.pixelData[y][x] = (short) raster.getSample(x, y, 0);
                }
            }
            
            reader.dispose();
            iis.close();
        }
        
        return slice;
    }

    private void buildVolumeData() {
        if (dicomSlices.isEmpty()) return;
        
        volumeData = new short[volumeDepth][volumeHeight][volumeWidth];
        
        for (int z = 0; z < volumeDepth && z < dicomSlices.size(); z++) {
            DicomSlice slice = dicomSlices.get(z);
            if (slice.pixelData != null) {
                for (int y = 0; y < volumeHeight && y < slice.pixelData.length; y++) {
                    for (int x = 0; x < volumeWidth && x < slice.pixelData[y].length; x++) {
                        volumeData[z][y][x] = slice.pixelData[y][x];
                    }
                }
            }
        }
    }

    private void setupSlidersForLoadedVolume() {
        isUpdatingSliders = true;
        
        // Update slider ranges
        axialSlider.setMax(volumeDepth - 1);
        coronalSlider.setMax(volumeHeight - 1);
        sagittalSlider.setMax(volumeWidth - 1);
        
        // Set initial positions to center
        axialSlider.setValue(volumeDepth / 2);
        coronalSlider.setValue(volumeHeight / 2);
        sagittalSlider.setValue(volumeWidth / 2);
        
        isUpdatingSliders = false;
    }
}