package com.dicom.viewer;

import java.awt.image.BufferedImage;

public class DicomSlice {
    public BufferedImage image;
    public short[][] pixelData;
    public double[] imagePosition = new double[3];
    public double[] imageOrientation = new double[6];
    public double[] pixelSpacing = new double[2];
    public double sliceThickness = 1.0;
    public int rows, columns;
    public String instanceUID;
    public int sliceLocation;
    public double windowCenter = 128;
    public double windowWidth = 256;
} 