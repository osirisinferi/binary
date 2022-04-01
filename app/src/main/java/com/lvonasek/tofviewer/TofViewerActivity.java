package com.lvonasek.tofviewer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class TofViewerActivity extends Activity
    implements GLSurfaceView.Renderer, ImageReader.OnImageAvailableListener {

  private static final int REQUEST_CAMERA_PERMISSION = 200;

  private static final String TAG = TofViewerActivity.class.getSimpleName();

  private final DepthmapRenderer depthmapRenderer = new DepthmapRenderer();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

    GLSurfaceView surfaceView = findViewById(R.id.glsurfaceview);
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    depthmapRenderer.initCamera(this);
  }

  // CPU image reader callback.
  @Override
  public void onImageAvailable(ImageReader imageReader) {
    Image image = imageReader.acquireLatestImage();
    if (image == null) {
      Log.w(TAG, "onImageAvailable: Skipping null image.");
      return;
    }

    Image.Plane plane = image.getPlanes()[0];
    ShortBuffer shortDepthBuffer = plane.getBuffer().asShortBuffer();
    ArrayList<Short> pixel = new ArrayList<>();
    while (shortDepthBuffer.hasRemaining()) {
      pixel.add(shortDepthBuffer.get());
    }
    int stride = plane.getRowStride();

    int offset = 0;
    float[] outputRGB = new float[image.getWidth() * image.getHeight()];
    float[] output = new float[image.getWidth() * image.getHeight()];
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        int depthSample = pixel.get((y / 2) * stride + x);
        int depthRange = (depthSample & 0x1FFF);
        int depthConfidence = ((depthSample >> 13) & 0x7);
        float depthPercentage = depthConfidence == 0 ? 1.f : (depthConfidence - 1) / 7.f;
        output[offset + x] = 0.001f * depthRange;
        outputRGB[offset + x] = depthPercentage;
      }
      offset += image.getWidth();
    }
    image.close();

    try {
      depthmapRenderer.update(output, outputRGB);
    } catch (Exception e) {
      //the device returned depthmap with wrong resolution
      e.printStackTrace();
    }
  }

  // Android focus change callback.
  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  // GL surface created callback. Will be called on the GL thread.
  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      depthmapRenderer.createOnGlThread(this);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  // GL surface changed callback. Will be called on the GL thread.
  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    GLES20.glViewport(0, 0, width, height);
  }

  // GL draw callback. Will be called each frame on the GL thread.
  @Override
  public void onDrawFrame(GL10 gl) {
    // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    try {
      depthmapRenderer.draw(this, 1);
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  /**
   * An {@link ImageReader} that handles still image capture.
   */
  private ImageReader mImageReader;
  private CameraDevice mCameraDevice;

  @Override
  public void onResume() {
    super.onResume();
    openCamera();
  }

  @Override
  public void onPause() {
    closeCamera();
    super.onPause();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == REQUEST_CAMERA_PERMISSION) {
      if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
        finish();
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
  }

  /**
   * Opens the camera
   */
  private void openCamera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
      return;
    }
    mImageReader = ImageReader.newInstance(depthmapRenderer.getDepthWidth(),
                                           depthmapRenderer.getDepthHeight(),
                                           ImageFormat.DEPTH16, 5);
    mImageReader.setOnImageAvailableListener(this, null);
    CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      manager.openCamera(depthmapRenderer.getDepthCameraId(), callBack, null);
    } catch (CameraAccessException e) {
      e.printStackTrace();
    } catch (Exception e) {
      throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
    }
  }

  /**
   * Closes the current {@link CameraDevice}.
   */
  private void closeCamera() {
    if (null != mImageReader) {
      mImageReader.close();
      mImageReader = null;
    }
    if (null != mCameraDevice)
    {
      mCameraDevice.close();
      mCameraDevice = null;
    }
  }

  CameraDevice.StateCallback callBack = new CameraDevice.StateCallback() {
    @Override
    public void onOpened(CameraDevice cameraDevice) {
      Surface imageReaderSurface = mImageReader.getSurface();
      mCameraDevice = cameraDevice;

      try {
        final CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        requestBuilder.addTarget(imageReaderSurface);

        cameraDevice.createCaptureSession(Collections.singletonList(imageReaderSurface),new CameraCaptureSession.StateCallback() {

          @Override
          public void onConfigured(CameraCaptureSession cameraCaptureSession) {
            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {};

            try {
              HandlerThread handlerThread = new HandlerThread("DepthBackgroundThread");
              handlerThread.start();
              Handler handler = new Handler(handlerThread.getLooper());
              cameraCaptureSession.setRepeatingRequest(requestBuilder.build(),captureCallback,handler);

            } catch (CameraAccessException e) {
              e.printStackTrace();
            }
          }
          @Override
          public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

          }
        },null);
      } catch (CameraAccessException e) {
        e.printStackTrace();
      }
    }
    @Override
    public void onDisconnected(CameraDevice cameraDevice) {
    }
    @Override
    public void onError(CameraDevice cameraDevice, int i) {
    }
  };
}
