package com.example.zenonavigation;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.zenonavigation.customview.OverlayView;
import com.example.zenonavigation.env.BorderedText;
import com.example.zenonavigation.env.ImageUtils;
import com.example.zenonavigation.env.Logger;
import com.example.zenonavigation.maps.GetDirectionsData;
import com.example.zenonavigation.tflite.Classifier;
import com.example.zenonavigation.tflite.TFLiteObjectDetectionAPIModel;
import com.example.zenonavigation.tracking.MultiBoxTracker;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.maps.android.SphericalUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;


public class Main2Activity extends AppCompatActivity implements
        OnMapReadyCallback,
        OnImageAvailableListener
        , Camera.PreviewCallback
{



  private static Fragment fragment;

  private FrameLayout nightVision;

  private boolean nightVisionOn = false;
  private boolean gps_enabled = false;
  private boolean network_enabled = false;
  private String travelMode;
  private boolean hybridMap;

  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private boolean debug = false;
  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(800, 600);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private BorderedText borderedText;


  private LocationRequest locationRequest;
  private LocationCallback locationCallback;
  private FusedLocationProviderClient fusedLocationProviderClient;


  private boolean navigating;

  private GoogleMap mMap;
  private LatLng origin;
  private LatLng destination;

  private Marker marker;

  private CameraPosition currentMapPos;

  private FloatingActionButton buttonMyLocation;

  private ImageButton buttonDetect;
  private ImageButton buttonNightVision;
  private TextView textNightVision;



  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.tfe_od_activity_camera);




    if (hasCameraPermission())
    {
      setFragment();
    }
    else
    {
      requestCameraPermission();
    }





    nightVision = findViewById(R.id.nightVision);




    /**
     *
     *
     *
     Location */

    SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
            .findFragmentById(R.id.map);
    mapFragment.getMapAsync(this);

    fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
    locationRequest = new LocationRequest();
    locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    locationRequest.setMaxWaitTime(0);
    locationRequest.setInterval(0);
    locationRequest.setFastestInterval(0);
    locationRequest.setSmallestDisplacement(0);

    locationCallback = new LocationCallback(){
      @Override
      public void onLocationResult(LocationResult locationResult) {
        if (locationResult == null) {
          return;
        }
        for (Location location : locationResult.getLocations()) {
          if (location != null) {

            origin = new LatLng(location.getLatitude(), location.getLongitude());

            if (navigating) {

              CameraPosition oldPos = mMap.getCameraPosition();
              CameraPosition newPos = CameraPosition.builder(oldPos)
                      .target(origin)
                      .bearing(location.getBearing())
                      .tilt(55)
                      .build();
              mMap.animateCamera(CameraUpdateFactory.newCameraPosition(newPos));




              double totalDistance = SphericalUtil.computeDistanceBetween(origin, destination);
              if (totalDistance < 5)
              {
                navigating = false;
                mMap.clear();
                destination = null;
                getDeviceLocation();
              }



            }

          }
        }

      }

    };

    fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper());
















    /**
     *
     *
     Controls */


    textNightVision = findViewById(R.id.textNightVision);




    buttonMyLocation = findViewById(R.id.buttonMyLocation);
    buttonMyLocation.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        checkGpsPermission();
        getGpsPermission();
        getDeviceLocation();
      }
    });





    buttonDetect = findViewById(R.id.buttonDetect);
    buttonDetect.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        finish();
      }
    });





    buttonNightVision = findViewById(R.id.buttonNightVision);
    buttonNightVision.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (!nightVisionOn)
        {
          nightVision.setBackgroundColor(getResources().getColor(R.color.nightVision_transparent, null));
          textNightVision.setTextColor(getResources().getColor(R.color.colorAccent, null));
          nightVisionOn = true;
        }
        else
        {
          nightVision.setBackgroundColor(getResources().getColor(R.color.full_transparent, null));
          textNightVision.setTextColor(getResources().getColor(R.color.colorWhite, null));
          nightVisionOn = false;
        }
      }
    });





  }





















  //Maps and Navigation
  @Override
  public void onMapReady(GoogleMap googleMap) {
    mMap = googleMap;
    mMap.getUiSettings().setMyLocationButtonEnabled(false);
    mMap.getUiSettings().setMapToolbarEnabled(false);
    mMap.getUiSettings().setCompassEnabled(false);
    mMap.setBuildingsEnabled(true);

    checkGpsPermission();

    try{
      mMap.setMyLocationEnabled(true);
      mMap.setTrafficEnabled(true);
    }
    catch (SecurityException e)  {
      Log.e("Exception: %s", e.getMessage());
    }


    currentMapPos = MainActivity.getCurrentMapPos();
    mMap.moveCamera(CameraUpdateFactory.newCameraPosition(currentMapPos));

    navigating = MainActivity.getNavigating();
    origin = MainActivity.getOrigin();
    destination = MainActivity.getDestination();
    travelMode = MainActivity.getTravelMode();
    hybridMap = MainActivity.gethybridMap();

      if (hybridMap)
      {
          mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
      }
      else
      {
          mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
      }

    if (navigating && origin!=null && destination!=null)
    {
      requestDirections(origin, destination, travelMode);
      mMap.addMarker(new MarkerOptions().position(destination));
    }


    mMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
      @Override
      public void onMapClick(LatLng latLng) {
        finish();
      }
    });

  }

  public void getDeviceLocation() {
    try {
      if (gps_enabled || network_enabled) {
        fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                  @Override
                  public void onSuccess(Location location) {
                    if (location != null) {
                      origin = new LatLng(location.getLatitude(), location.getLongitude());

                      if (navigating)
                      {
                        CameraPosition cameraPosition = new CameraPosition.Builder()
                                .target(origin)      // Sets the center of the map
                                .zoom(17)                   // Sets the zoom
                                .bearing(location.getBearing())                // Sets the orientation of the camera
                                .tilt(55)                   // Sets the tilt of the camera
                                .build();                   // Creates a CameraPosition from the builder
                        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

                      }
                      else
                      {
                        CameraPosition cameraPosition = new CameraPosition.Builder()
                                .target(origin)      // Sets the center of the map
                                .zoom(17)                   // Sets the zoom
                                .bearing(0)                // Sets the orientation of the camera
                                .tilt(0)                   // Sets the tilt of the camera
                                .build();                   // Creates a CameraPosition from the builder
                        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                      }

                    }
                  }
                });
      }
    } catch(SecurityException e)  {
      Log.e("Exception: %s", e.getMessage());
    }
  }

  private void checkGpsPermission()
  {
    LocationManager lm = (LocationManager)
            getSystemService(Context. LOCATION_SERVICE ) ;
    try {
      gps_enabled = lm.isProviderEnabled(LocationManager. GPS_PROVIDER ) ;
    } catch (Exception e) {
      e.printStackTrace() ;
    }
    try {
      network_enabled = lm.isProviderEnabled(LocationManager. NETWORK_PROVIDER ) ;
    } catch (Exception e) {
      e.printStackTrace() ;
    }
  }

  private void getGpsPermission () {
    if (!gps_enabled && !network_enabled) {
      new AlertDialog.Builder(Main2Activity. this )
              .setMessage( "Turn on device location in settings" )
              .setPositiveButton( "GO TO SETTINGS" , new
                      DialogInterface.OnClickListener() {
                        @Override
                        public void onClick (DialogInterface paramDialogInterface , int paramInt) {
                          startActivity( new Intent(Settings. ACTION_LOCATION_SOURCE_SETTINGS )) ;
                        }
                      })
              .setNegativeButton("NO", null)
              .show() ;
    }
  }

  public void requestDirections(LatLng origin, LatLng destination, String travelMode)
  {
    StringBuilder sb = new StringBuilder();
    sb.append("https://maps.googleapis.com/maps/api/directions/json?");
    sb.append("origin="+origin.latitude+","+origin.longitude);
    sb.append("&destination="+destination.latitude+","+destination.longitude);
    sb.append("&departure_time=now");
    sb.append("&mode="+travelMode);
    sb.append("&key="+getString(R.string.google_maps_key));

    Object[] dataTransfer = new Object[2];
    dataTransfer[0] = mMap;
    dataTransfer[1] = sb.toString();

    GetDirectionsData getDirectionsData = new GetDirectionsData(getApplicationContext());
    getDirectionsData.execute(dataTransfer);


  }

    public void fullScreen()
    {
        final View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus) {
            fullScreen();
        }
    }




























//Camera and Detection

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /** Callback for android.hardware.Camera API */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;
    yRowStride = previewWidth;

    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
          new Runnable() {
            @Override
            public void run() {
              ImageUtils.convertYUV420ToARGB8888(
                  yuvBytes[0],
                  yuvBytes[1],
                  yuvBytes[2],
                  previewWidth,
                  previewHeight,
                  yRowStride,
                  uvRowStride,
                  uvPixelStride,
                  rgbBytes);
            }
          };

      postInferenceCallback =
          new Runnable() {
            @Override
            public void run() {
              image.close();
              isProcessingFrame = false;
            }
          };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();

  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    fullScreen();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }


  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST) {
      if (cameraPermissionsGranted(grantResults)) {
        setFragment();
      } else {
        requestCameraPermission();
      }
    }
  }

  private static boolean cameraPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasCameraPermission() {
    return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
  }

  private void requestCameraPermission() {
    if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
      Toast.makeText(
              Main2Activity.this,
              "Camera permission is required",
              Toast.LENGTH_LONG)
          .show();
    }
    requestPermissions(new String[] {PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
            (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();

    //Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
          CameraConnectionFragment.newInstance(
              new CameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  Main2Activity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;

    } else {
      fragment =
          new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }


  //detector

  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
              TFLiteObjectDetectionAPIModel.create(
                      getAssets(),
                      TF_OD_API_MODEL_FILE,
                      TF_OD_API_LABELS_FILE,
                      TF_OD_API_INPUT_SIZE,
                      TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing classifier!");
      Toast toast =
              Toast.makeText(
                      getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888);

    frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
            new OverlayView.DrawCallback() {
              @Override
              public void drawCallback(final Canvas canvas) {
                tracker.draw(canvas);
                if (isDebug()) {
                  tracker.drawDebug(canvas);
                }
              }
            });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }


  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
            new Runnable() {
              @Override
              public void run() {
                LOGGER.i("Running detection on image " + currTimestamp);
                final long startTime = SystemClock.uptimeMillis();
                final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                final Canvas canvas = new Canvas(cropCopyBitmap);
                final Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.0f);

                float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                switch (MODE) {
                  case TF_OD_API:
                    minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                    break;
                }

                final List<Classifier.Recognition> mappedRecognitions =
                        new LinkedList<Classifier.Recognition>();

                for (final Classifier.Recognition result : results) {
                  final RectF location = result.getLocation();
                  if (location != null && result.getConfidence() >= minimumConfidence) {
                    canvas.drawRect(location, paint);

                    cropToFrameTransform.mapRect(location);

                    result.setLocation(location);
                    mappedRecognitions.add(result);
                  }
                }

                tracker.trackResults(mappedRecognitions, currTimestamp);
                trackingOverlay.postInvalidate();

                computingDetection = false;

                runOnUiThread(
                        new Runnable() {
                          @Override
                          public void run() {
                            //showFrameInfo(previewWidth + "x" + previewHeight);
                            //showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                            //showInference(lastProcessingTimeMs + "ms");
                          }
                        });
              }
            });
  }


  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }


  protected Size getDesiredPreviewFrameSize() {
    //View container = findViewById(R.id.container);
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API
  }





}


