package com.test.camera2apitest;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class CameraActivity extends AppCompatActivity {

    //Define variables for button and textureView
    Button button;
    TextureView textureView;

    /* Define a SparseIntArray. This is used to hold the orientation of the camera and
        convert from screen rotation to JPEG orientation. */

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    //Append orientations to the SparseIntArray.
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }


    private String cameraId; //Holds ID of the camera device

    CameraDevice cameraDevice;
    CameraCaptureSession CCS;
    CaptureRequest captureRequest;
    CaptureRequest.Builder CRB;

    private Size imgDim; //hold dimensions of the image.
    private ImageReader imageReader;
    private File file;
    Handler mBackgroundHandler; //Run tasks in the background
    HandlerThread mBackgroundThread; //Thread for running tasks that shouldn't block the UI.


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_camera);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        //Find views
        textureView = findViewById(R.id.textureView);
        button = findViewById(R.id.captureButton);
        textureView.setSurfaceTextureListener(textureListener);

        //Save image when button clicked.
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    takePicture();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    //Check if permissions are allowed for the camera.

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), "Cannot Start Camera Permission Denied!", Toast.LENGTH_LONG).show();
                //finish();

            }
        }
    }

    //Create new TextureView.SurfaceTextureListener.
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            try {
                openCamera(); //Call open camera function.
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }

        //Invoked when a TextureView's SurfaceTexture is ready for use.
    };

    //A callback object for receiving updates about the state of a camera device.
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                createCameraPreview(); //When the camera is opened start the preview.
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            cameraDevice.close(); //Close camera if there is an error opening it.
            cameraDevice = null;
        }
    };

    private void createCameraPreview() throws CameraAccessException {
        //Display preview on texture view.
        SurfaceTexture texture = textureView.getSurfaceTexture();
        Objects.requireNonNull(texture).setDefaultBufferSize(imgDim.getWidth(), imgDim.getHeight());
        Surface surface = new Surface(texture);

        //Create a CaptureRequest.Builder for new capture requests, initialized with template for a target use case.
        CRB = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        CRB.addTarget(surface); //Add camera request builder to surface.
        //Create a new camera capture session by providing the target output set of Surfaces to the camera device.
        cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            //This method is called when the camera device has finished configuring itself, and the session can start processing capture requests.
            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                if (cameraDevice == null) {
                    return;
                }

                CCS = cameraCaptureSession;
                try {
                    updatePreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                Toast.makeText(getApplicationContext(), "Configuration Changed", Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    private void updatePreview() throws CameraAccessException {
        if (cameraDevice == null) {
            return;
        }

        CRB.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        CCS.setRepeatingRequest(CRB.build(), null, mBackgroundHandler);
    }

    //Open the camera.
    private void openCamera() throws CameraAccessException {

        //Connect to system camera.
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        cameraId = manager.getCameraIdList()[0]; //0 = rear camera.

        CameraCharacteristics characteristics = manager.getCameraCharacteristics((cameraId));

        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        assert map != null;
        imgDim = map.getOutputSizes(SurfaceTexture.class)[0]; //get image dimensions.


        //Check if required permissions are available.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(CameraActivity.this,
                    new String[]
                            {Manifest.permission.CAMERA,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
            return;
        }

        manager.openCamera(cameraId, stateCallback, null); //Open a connection to a camera with the given ID.

    }

    //Take a picture when button pressed.
    private void takePicture() throws CameraAccessException {
        if (cameraDevice == null) {
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId()); //Properties describing a CameraDevice.

        Size[] jpegSizes = null;

        jpegSizes = Objects.requireNonNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).getOutputSizes(ImageFormat.JPEG);

        int width = 640;
        int height = 480;

        if (jpegSizes != null && jpegSizes.length > 0) {
            width = jpegSizes[0].getWidth();
            height = jpegSizes[0].getHeight();
        }

        //Render image data onto surface.
        final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
        List<Surface> outputSurfaces = new ArrayList<>(2);
        outputSurfaces.add(reader.getSurface());

        outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));

        final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(reader.getSurface());
       // captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        // Auto focus should be continuous for camera preview.
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);


        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

        //Setting WhiteBalance
        // 3. Turn off auto white balance
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
        captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        // 4. Manually set the white balance to approximate 1400K
        // These values are an approximation and may need adjustment based on the specific camera sensor
        RggbChannelVector rggbChannelVector = new RggbChannelVector(2.0f, 1.5f, 1.5f, 3.0f); // Example gains
        captureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggbChannelVector);

        // Optional: Set a custom color transform matrix (if needed)
        // Typically, for low color temperatures like 1400K, the matrix would emphasize red and green channels
        /*ColorSpaceTransform colorTransform = new ColorSpaceTransform(new int[]{
                3000, 1000, 1000, // Red
                1000, 3000, 1000, // Green
                1000, 1000, 3000  // Blue
        });
        captureBuilder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, colorTransform);*/

        // Set the desired ISO value
        int desiredISO = 125; // Replace with your desired ISO value
        captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, desiredISO);

        // Set exposure compensation to 0
        captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);

        //Set exposure time
        long exposureTime = 10000000L; // 1/100 seconds in nanoseconds
        captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

        float apertureValue = 2.8f; // Example aperture value, ensure this is supported by your device
        captureBuilder.set(CaptureRequest.LENS_APERTURE, apertureValue);

        // Get the physical focal length
        float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        assert focalLengths != null;
        float physicalFocalLength = focalLengths[0]; // Using the first available focal length

        // Get the sensor active array size
        Rect activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        // Calculate the crop region to simulate the desired focal length
        float scale = physicalFocalLength / 24;

        assert activeArraySize != null;
        int cropWidth = (int) (activeArraySize.width() / scale);
        int cropHeight = (int) (activeArraySize.height() / scale);

        int cropX = (activeArraySize.width() - cropWidth) / 2;
        int cropY = (activeArraySize.height() - cropHeight) / 2;

        Rect cropRegion = new Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight);

        // Apply the crop region to the capture request
        captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);

        // Get the focal lengths supported by the camera
        //float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        for (float focalLength : focalLengths) {
            Log.d("Camera2API", "Supported focal length: " + focalLength + " mm");
        }

        //Get system time.
        long tsLong = System.currentTimeMillis() / 1000;
        String ts = Long.toString(tsLong);

        //Create new file.
        file = new File((Environment.getExternalStorageDirectory() + "/" + ts + ".jpg"));

        //Callback interface for being notified that a new image is available.
        ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Image image = null;

                image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                try {
                    save(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    image.close();
                }


            }
        };

        //Register a listener to be invoked when a new image becomes available from the ImageReader.
        reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

        //When capture complete.
        final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                Toast.makeText(getApplicationContext(), "Image Saved!", Toast.LENGTH_LONG).show();
                try {
                    createCameraPreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        };

        cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                try {
                    //Submit a request for an image to be captured by the camera device.
                    cameraCaptureSession.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

            }
        }, mBackgroundHandler);

    }

    //Save output steam into file.
    private void save(byte[] bytes) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Camera2Image.jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        OutputStream outputStream = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assert uri != null;
            outputStream = getContentResolver().openOutputStream(uri);
            //outputStream = Files.newOutputStream(file.toPath());
        }

        assert outputStream != null;
        outputStream.write(bytes);

        outputStream.close();
    }

    //Resume background thread.
    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();
        if (textureView.isAvailable()) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }

    }

    //Start background thread.
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    @Override
    protected void onPause() {
        try {
            stopBackgroundThread();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.onPause();
    }

    //Stop background thread safely.
    protected void stopBackgroundThread() throws InterruptedException {
        mBackgroundThread.quitSafely();
        mBackgroundThread.join();
        mBackgroundThread = null;
        mBackgroundHandler = null;

    }
}