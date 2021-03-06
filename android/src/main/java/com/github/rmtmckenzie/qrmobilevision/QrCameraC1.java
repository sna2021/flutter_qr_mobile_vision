package com.github.rmtmckenzie.qrmobilevision;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Implements QrCamera using Deprecated Camera API
 * NOTE: uses fully qualified names for android.hardware.Camera
 * so that deprecation warnings can be avoided.
 */
@TargetApi(16)
class QrCameraC1 implements QrCamera, Camera.AutoFocusCallback {

    private static final String TAG = "cgr.qrmv.QrCameraC1";
    private static final int IMAGEFORMAT = ImageFormat.NV21;
    private final SurfaceTexture texture;
    private final QrDetector detector;
    private android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
    private int targetWidth, targetHeight;
    private android.hardware.Camera camera = null;
    private Context context;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    QrCameraC1(int width, int height, SurfaceTexture texture, Context context, QrDetector detector) {
        this.texture = texture;
        targetHeight = height;
        targetWidth = width;
        this.detector = detector;
        this.context = context;
    }

    private int getFirebaseOrientation() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int deviceRotation = windowManager.getDefaultDisplay().getRotation();
        int rotationCompensation = (ORIENTATIONS.get(deviceRotation) + info.orientation + 270) % 360;

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        int result;
        switch (rotationCompensation) {
            case 0:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                break;
            case 90:
                result = FirebaseVisionImageMetadata.ROTATION_90;
                break;
            case 180:
                result = FirebaseVisionImageMetadata.ROTATION_180;
                break;
            case 270:
                result = FirebaseVisionImageMetadata.ROTATION_270;
                break;
            default:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                Log.e(TAG, "Bad rotation value: " + rotationCompensation);
        }
        return result;
    }
    @Override
    public void start() throws QrReader.Exception {
        int numberOfCameras = android.hardware.Camera.getNumberOfCameras();
        info = new android.hardware.Camera.CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            android.hardware.Camera.getCameraInfo(i, info);
            if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
                camera = android.hardware.Camera.open(i);
                break;
            }
        }

        if (camera == null) {
            throw new QrReader.Exception(QrReader.Exception.Reason.noBackCamera);
        }

        final android.hardware.Camera.Parameters parameters = camera.getParameters();

        List<String> focusModes = parameters.getSupportedFocusModes();

        if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_AUTO);
        } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)){
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)){
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_EDOF)){
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_EDOF);
        } else  {
            Log.i(TAG, "Initializing with autofocus off as not supported.");
        }

        List<android.hardware.Camera.Size> supportedSizes = parameters.getSupportedPreviewSizes();
        android.hardware.Camera.Size size = getAppropriateSize(supportedSizes);

        parameters.setPreviewSize(size.width, size.height);
        texture.setDefaultBufferSize(size.width, size.height);

        parameters.setPreviewFormat(IMAGEFORMAT);

        try {
            camera.setPreviewCallback(new android.hardware.Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, android.hardware.Camera camera) {
                    android.hardware.Camera.Size previewSize = camera.getParameters().getPreviewSize();
                    if (data != null) {
                        QrDetector.Frame frame = new Frame(data, new FirebaseVisionImageMetadata.Builder()
                            .setFormat(IMAGEFORMAT)
                            .setWidth(previewSize.width)
                            .setHeight(previewSize.height)
                            .setRotation(getFirebaseOrientation())
                            .build());
                        detector.detect(frame);
                    }
                }
            });

            camera.setPreviewTexture(texture);
            camera.startPreview();
            camera.autoFocus(this);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        try {
            camera.cancelAutoFocus();
            camera.autoFocus(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    static class Frame implements QrDetector.Frame {
        private byte[] data;
        private final FirebaseVisionImageMetadata metadata;

        Frame(byte[] data, FirebaseVisionImageMetadata metadata) {
            this.data = data;
            this.metadata = metadata;
        }

        @Override
        public FirebaseVisionImage toImage() {
            return FirebaseVisionImage.fromByteArray(data, metadata);
        }

        @Override
        public void close() {
            data = null;
        }
    }

    @Override
    public int getWidth() {
        return camera.getParameters().getPreviewSize().height;
    }

    @Override
    public int getHeight() {
        return camera.getParameters().getPreviewSize().width;
    }

    @Override
    public int getOrientation() {
        return (info.orientation + 270) % 360;
    }

    @Override
    public void stop() {
        try {
            camera.cancelAutoFocus();
            camera.autoFocus(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        camera.stopPreview();
        camera.setPreviewCallback(null);
        camera.release();
    }

    //Size here is Camera.Size, not android.util.Size as in the QrCameraC2 version of this method
    private android.hardware.Camera.Size getAppropriateSize(List<android.hardware.Camera.Size> sizes) {
        // assume sizes is never 0
        Log.d(TAG, "getAppropriatePreviewSize: sizes " + Arrays.toString(sizes.toArray()));
        if (sizes.size() == 1) {
            return sizes.get(0);
        }

        android.hardware.Camera.Size s = sizes.get(0);
        android.hardware.Camera.Size s1 = sizes.get(1);

        if (s1.width > s.width || s1.height > s.height) {
            // ascending
            if (info.orientation % 180 == 0) {
                for (android.hardware.Camera.Size size : sizes) {
                    s = size;
                    if (size.height > targetHeight && size.width > targetWidth) {
                        break;
                    }
                }
            } else {
                for (android.hardware.Camera.Size size : sizes) {
                    s = size;
                    if (size.height > targetWidth && size.width > targetHeight) {
                        break;
                    }
                }
            }
        } else {
            // descending
            if (info.orientation % 180 == 0) {
                for (android.hardware.Camera.Size size : sizes) {
                    if (size.height < targetHeight || size.width < targetWidth) {
                        break;
                    }
                    s = size;
                }
            } else {
                for (android.hardware.Camera.Size size : sizes) {
                    if (size.height < targetWidth || size.width < targetHeight) {
                        break;
                    }
                    s = size;
                }
            }
        }
        Log.d(TAG, "getAppropriatePreviewSize: selected " + s.toString());
        return s;
    }
}


