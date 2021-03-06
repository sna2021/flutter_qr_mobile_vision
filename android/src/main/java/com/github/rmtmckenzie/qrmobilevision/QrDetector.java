package com.github.rmtmckenzie.qrmobilevision;

import android.content.Context;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.util.List;

/**
 * Allows QrCamera classes to send frames to a Detector
 */

class QrDetector implements OnSuccessListener<List<FirebaseVisionBarcode>>, OnFailureListener {
    private static final String TAG = "cgr.qrmv.QrDetector";
    private final QrReaderCallbacks communicator;
    private final FirebaseVisionBarcodeDetector detector;
    private final Long timeoutMillis;

    public interface Frame {
        FirebaseVisionImage toImage();

        void close();
    }

    @GuardedBy("this")
    private Frame latestFrame;

    @GuardedBy("this")
    private Frame processingFrame;

    @GuardedBy("this")
    private Long latestTime;



    QrDetector(QrReaderCallbacks communicator, FirebaseVisionBarcodeDetectorOptions options,Long timeoutMillis) {
        this.communicator = communicator;
        this.detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options);
        if (timeoutMillis == null){
            this.timeoutMillis = 0L;
        }else  {
            this.timeoutMillis = timeoutMillis;
        }
    }

    void detect(Frame frame) {
        if (latestTime == null) latestTime = System.currentTimeMillis();

        if (System.currentTimeMillis() - timeoutMillis > latestTime){
            latestTime = System.currentTimeMillis();
            Log.d(TAG, "detect: start new frame ");
            if (latestFrame != null) latestFrame.close();
            latestFrame = frame;
            if (processingFrame == null) {
                processLatest();
            }
        } else {
          frame.close();
        }
    }

    private synchronized void processLatest() {
        if (processingFrame != null) processingFrame.close();
        processingFrame = latestFrame;
        latestFrame = null;
        if (processingFrame != null) {
            processFrame(processingFrame);
        }
    }

    private void processFrame(Frame frame) {
        FirebaseVisionImage image;
        try {
            image = frame.toImage();
        } catch (IllegalStateException ex) {
            // ignore state exception from making frame to image
            // as the image may be closed already.
            return;
        }

        detector.detectInImage(image)
            .addOnSuccessListener(this)
            .addOnFailureListener(this);
    }

    @Override
    public void onSuccess(List<FirebaseVisionBarcode> firebaseVisionBarcodes) {
        for (FirebaseVisionBarcode barcode : firebaseVisionBarcodes) {
            communicator.qrRead(barcode.getRawValue());
        }
        processLatest();
    }

    @Override
    public void onFailure(@NonNull Exception e) {
        Log.w(TAG, "Barcode Reading Failure: ", e);
    }
}
