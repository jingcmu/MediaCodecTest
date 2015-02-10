/*
 * Copyright 2014 The Android Open Source Project

 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.mediacodectest;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Record video from the camera preview and encode it as an ivf file.  Demonstrates the use
 * of ediaCodec with Camera input.  Does not record audio.
 */
public class CameraToIvfTest {
    private static final String TAG = "VP8CodecTestBase";

    // where to put the output file (note: /sdcard requires WRITE_EXTERNAL_STORAGE permission)
    private static final File OUTPUT_DIR = Environment.getExternalStorageDirectory();

    // 720p
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FRAME_RATE = 30;   // 30fps
    private static final int BITRATE = 2000000;   // 2 Mbps
    //private static final int BITRATE = 2000000;   // 1 Mbps

    // VGA
    //private static final int WIDTH = 640;
    //private static final int HEIGHT = 480;
    //private static final int FRAME_RATE = 15;   // 30fps
    //private static final int BITRATE = 1000000;   // 1 Mbps

    // parameters for the encoder
    private static final String VP8_MIME = "video/x-vnd.on2.vp8";
    //private static final String VP8_MIME = "video/avc";
    private static final int IFRAME_INTERVAL = 30;         // 10 seconds between I-frames
    private static final int DURATION_SEC = 10;           // 10 seconds of video
    private static boolean WRITE_IVF = false;
    private static boolean FORCE_SW_CODEC = false;

    // encoder / ivf writer state
    private MediaCodec mEncoder;
    private MediaCodec mDecoder;
    private Surface mEncoderSurface;
    private byte[] mEncoderData; // encoder output
    private long mEncoderDataTimestampUs;
    private CodecInputSurface mInputSurface;
    private CameraBufferCallback mCameraCallback;
    private IvfWriter mIvfWriter;
    private LooperRunner mCameraLooperRunner;
    private LooperRunner mTestLooperRunner;
    private Thread mTestRunner;
    private int mInputFrameCount;
    private int mOutputFrameCount;
    private int mDroppedFrameCount;
    private int mEncodedSize;
    private int mEncoderColorFormat;
    private long[] mFrameInputTimeMs = new long[DURATION_SEC * FRAME_RATE + 1024];
    private long[] mFrameOutputTimeMs = new long[DURATION_SEC * FRAME_RATE + 1024];
    private long[] mEncoderPresentationTimeMs = new long[DURATION_SEC * FRAME_RATE + 1024];
    private long[] mEncoderFrameOutputSize = new long[DURATION_SEC * FRAME_RATE + 1024];
    private long[] mDecoderFrameInputTimeMs = new long[DURATION_SEC * FRAME_RATE + 1024];
    private long[] mDecoderFrameOutputTimeMs = new long[DURATION_SEC * FRAME_RATE + 1024];

    private int mDecoderInputFrameCount;
    private int mDecoderOutputFrameCount;

    // Encoder parameters
    private static final String OMX_SW_CODEC_PREFIX = "OMX.google";
    private static final String VPX_SW_DECODER_NAME = "OMX.google.vp8.decoder";
    private static final String VPX_SW_ENCODER_NAME = "OMX.google.vp8.encoder";
    private static final int VIDEO_ControlRateConstant = 2;
    private static final int COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m = 0x7FA30C04;
    // Allowable color formats supported by codec - in order of preference.
    private static final int[] mSupportedColorList = {
            CodecCapabilities.COLOR_FormatYUV420Planar,
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
            COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m
    };

    // camera state
    private Camera mCamera;
    private int mCameraFrameDropRatio;
    private SurfaceTextureManager mStManager;

    // allocate one of these up front so we don't need to do it every time
    private BufferInfo mBufferInfo;

    /** test entry point */
    public void testEncodeSurfaceCameraToIvf(final Surface screenSurface,
            final VideoRendererIf rendererIf) throws Throwable {
        Runnable rEnc = new Runnable() {
            public void run() {
                encodeCameraToIvfWithSurface(screenSurface, rendererIf);
            }
        };
        mTestRunner = new Thread(rEnc);
        mTestRunner.start();
    }

    public void testEncodeBufferCameraToIvf(final Surface screenSurface,
            final VideoRendererIf rendererIf) throws Throwable {
        mTestLooperRunner = new LooperRunner();
        mTestLooperRunner.requestStart();
        // Configure and open camera on looper thread
        mTestLooperRunner.runCallableNoWait( new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                encodeCameraToIvfWithBuffers(screenSurface, rendererIf);
                return null;
            }
        } );
    }

    public void stopEncodeCameraToIvf() throws Throwable {
//        CameraToIvfWrapper.stopTest();
    }


    private void encodeCameraToIvfWithBuffers(Surface screenSurface, VideoRendererIf rendererIf) {
        boolean useSurface = false;
        boolean useCameraTimestamps = true;
        boolean useDecoder = false;
        boolean useDecoderSurface = true;

        Log.d(TAG, "EncodeCameraToIvfWithBuffers Thread id = " + Thread.currentThread().getId());
        Log.d(TAG, VP8_MIME + " output " + WIDTH + "x" + HEIGHT + " @" + BITRATE);

        try {
            prepareEncoder(WIDTH, HEIGHT, BITRATE, useSurface);
            if (useDecoder) {
                prepareDecoder(WIDTH, HEIGHT, useDecoderSurface, screenSurface);
            }
            mCameraLooperRunner = new LooperRunner();
            mCameraLooperRunner.requestStart();
            // Configure and open camera on looper thread
            mCameraLooperRunner.runCallable( new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    prepareCamera(WIDTH, HEIGHT, FRAME_RATE, mEncoderColorFormat);
                    return null;
                }
            } );
            //prepareCamera(encWidth, encHeight, encFps);
            mCameraCallback = new CameraBufferCallback(mCamera, WIDTH, HEIGHT, mCameraFrameDropRatio);
            mCameraCallback.prepareCallback(mEncoderColorFormat);

            mCamera.startPreview();

            long startWhen = -1;
            long desiredEnd = 0;
            long inPresentationTimeNs = 0;
            long previousPresentationTimeNs = 0;
            mInputFrameCount = 0;
            mOutputFrameCount = 0;
            mDroppedFrameCount = 0;
            mEncodedSize = 0;
            mDecoderInputFrameCount = 0;
            mDecoderOutputFrameCount = 0;
            Log.d(TAG, "Start preview");
            ByteBuffer[] encoderInputBuffers = mEncoder.getInputBuffers();
            ByteBuffer[] decoderInputBuffers = null;
            if (useDecoder) {
                decoderInputBuffers = mDecoder.getInputBuffers();
            }

            while (true) {
                // Wait for available frame
                //mCameraCallback.awaitNewImage();

                // Wait for available frame periodically pinging the encoder for the output
                //getEncoderOutput(false);
                while (true) {
                    boolean frameAvailable = mCameraCallback.checkNewImage(2);

                    // check encoder output
                    getEncoderOutput(false);

                    // If encoder generated data submit it to decoder
                    if (useDecoder && mEncoderData != null) {
                        // Get encoder input buffer and fill it with camera data
                        int inputBufIndex = mDecoder.dequeueInputBuffer(1000000);
                        if (inputBufIndex >= 0) {
                            int dataLength = mEncoderData.length;
                            Log.d(TAG, "Decoder input frame # " + mDecoderInputFrameCount + ". TS: " +
                                    (mEncoderDataTimestampUs / 1000) + " ms. Size: " + dataLength);
                            mDecoderFrameInputTimeMs[mDecoderInputFrameCount] =
                                    SystemClock.elapsedRealtime();
                            decoderInputBuffers[inputBufIndex].clear();
                            decoderInputBuffers[inputBufIndex].put(mEncoderData);
                            decoderInputBuffers[inputBufIndex].rewind();
                            mDecoder.queueInputBuffer(inputBufIndex, 0, dataLength,
                                    mEncoderDataTimestampUs, 0);
                            mDecoderInputFrameCount++;
                            if (VP8_MIME == "video/avc" && dataLength < 30) {
                                // Ignore H.264 SPS/PPS NAL
                                mDecoderInputFrameCount--;
                            }
                        }
                        else {
                            Log.e(TAG, "Decoder is not ready: " + inputBufIndex);
                        }
                    }

                    // check decoder output
                    if (useDecoder) {
                        getDecoderOutput(useDecoderSurface, rendererIf);
                    }

                    if (frameAvailable) {
                        break;
                    }
                }

                // Check encoder output.
                //getEncoderOutput(false);

                // First image arrived - this is our reference start time
                if (startWhen < 0) {
                    startWhen = mCameraCallback.getTimestamp();
                    desiredEnd = startWhen + DURATION_SEC * 1000000000L;
                }
                // Check the time
                if (mCameraCallback.getTimestamp() > desiredEnd) {
                    break;
                }

                // Get camera frame timestamp
                if (useCameraTimestamps) {
                    inPresentationTimeNs = mCameraCallback.getTimestamp() - startWhen;
                } else {
                    inPresentationTimeNs = 1000000000L * mInputFrameCount / FRAME_RATE;
                }
                // Set the presentation time stamp from the SurfaceTexture's time stamp.
                Log.d(TAG, "Encoder input frame # " + mInputFrameCount + ". TS: " +
                        (inPresentationTimeNs / 1000000) + " ms. Duration: " +
                        ((inPresentationTimeNs - previousPresentationTimeNs + 500000) / 1000000) + " ms.");
                previousPresentationTimeNs = inPresentationTimeNs;

                // Submit camera data to encoder
                if (mInputFrameCount <= mOutputFrameCount + 1) {
                    // Get encoder input buffer and fill it with camera data
                    int inputBufIndex = mEncoder.dequeueInputBuffer(0);
                    if (inputBufIndex >= 0) {
                        byte[] data = mCameraCallback.getCameraData();
                        int dataLength = data.length;
                        mFrameInputTimeMs[mInputFrameCount] = SystemClock.elapsedRealtime();
                        encoderInputBuffers[inputBufIndex].clear();
                        encoderInputBuffers[inputBufIndex].put(data);
                        encoderInputBuffers[inputBufIndex].rewind();
                        mEncoder.queueInputBuffer(inputBufIndex, 0, dataLength,
                                inPresentationTimeNs / 1000, 0);
                        mInputFrameCount++;
                    }
                    else {
                        Log.w(TAG, "Encoder is not ready - drop frame: " + inputBufIndex);
                        mDroppedFrameCount++;
                    }
                }
                else {
                    mDroppedFrameCount++;
                    Log.w(TAG, "Encoder is behind - drop frame: " +
                            (mInputFrameCount - mOutputFrameCount));
                }

                // Return camera frame back
                mCameraCallback.addCallbackBuffer();
            }

            // send end-of-stream to encoder, and drain remaining output
            //getEncoderOutput(true);

            // Print some statistics.
            double bitrate = (double)mEncodedSize * 8 * 1e9 / inPresentationTimeNs;
            double fps = mInputFrameCount * 1e9 / inPresentationTimeNs;
            Log.d(TAG, "Encoding duration: " + (inPresentationTimeNs / 1000000) + " ms" +
                    ". Size: " + mEncodedSize + ". Bitrate: " + (int)bitrate + " bps" +
                    ". Fps: " + (int)(fps + 0.5));
            Log.d(TAG, "Encoder Frames In: " + mInputFrameCount + ". Out: " +
                    mOutputFrameCount + ". Dropped: " + mDroppedFrameCount);
            Log.d(TAG, "Camera frames: " + mCameraCallback.mFrameCount +
                    ". Dropped: " + mCameraCallback.mDroppedFrameCount);

            // Get average bitrates and fps.
            String bitrateList = "  Bitrate list: ";
            String fpsList = "  FPS list: ";
            int totalFrameSizePerSecond = 0;
            int framesPerSecond = 0;
            int currentSecond;
            int nextSecond = 0;
            for (int i = 0; i < mOutputFrameCount; i++) {
                currentSecond = (int) (mEncoderPresentationTimeMs[i] / 1000);
                boolean lastFrame = (i == mOutputFrameCount - 1);
                if (!lastFrame) {
                    nextSecond = (int) (mEncoderPresentationTimeMs[i + 1] / 1000);
                }
                totalFrameSizePerSecond += mEncoderFrameOutputSize[i];
                framesPerSecond++;
                if (lastFrame || nextSecond > currentSecond) {
                    int currentBitrate = totalFrameSizePerSecond * 8;
                    bitrateList += (currentBitrate + " ");
                    fpsList += (framesPerSecond + " ");
                    totalFrameSizePerSecond = 0;
                    framesPerSecond = 0;
                }
            }
            Log.d(TAG, bitrateList);
            Log.d(TAG, fpsList);

            // Get average encoding time
            long encodingTime = 0;
            for (int i = 0; i < mOutputFrameCount; i++) {
                encodingTime += (mFrameOutputTimeMs[i] - mFrameInputTimeMs[i]);
            }
            encodingTime /= mOutputFrameCount;
            Log.d(TAG, "Average encoding time: " + encodingTime + " ms.");
            // Get average decoding time
            if (useDecoder) {
                Log.d(TAG, "Decoder Frames In: " + mDecoderInputFrameCount + ". Out: " +
                        mDecoderOutputFrameCount);
                long decodingTime = 0;
                for (int i = 0; i < mDecoderOutputFrameCount; i++) {
                    decodingTime += (mDecoderFrameOutputTimeMs[i] - mDecoderFrameInputTimeMs[i]);
                }
                decodingTime /= mDecoderOutputFrameCount;
                Log.d(TAG, "Average decoding time: " + decodingTime + " ms.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // release everything we grabbed
            releaseCamera();
            releaseEncoder();
            if (useDecoder) {
                releaseDecoder();
            }
            mCameraCallback.release();
            mCameraCallback = null;
            mCameraLooperRunner.requestStop();
            mCameraLooperRunner = null;
        }
    }

    /**
     * Tests encoding of VP8 video from Camera input.  The output is saved as an ivf file.
     */
    public void encodeCameraToIvfWithSurface(Surface screenSurface, VideoRendererIf rendererIf) {
        // arbitrary but popular values
        boolean useSurface = true;
        boolean useDecoder = false;
        boolean useDecoderSurface = true;

        Log.d(TAG, VP8_MIME + " output " + WIDTH + "x" + HEIGHT + " @" + BITRATE);

        try {
            prepareEncoder(WIDTH, HEIGHT, BITRATE, useSurface);
            if (useDecoder) {
                prepareDecoder(WIDTH, HEIGHT, useDecoderSurface, screenSurface);
            }
            prepareCamera(WIDTH, HEIGHT, FRAME_RATE, mEncoderColorFormat);
            mInputSurface = new CodecInputSurface(mEncoderSurface);
            mInputSurface.makeCurrent();
            prepareSurfaceTexture(mCameraFrameDropRatio);

            mCamera.startPreview();

            long startWhen = 0;
            long desiredEnd = 0;
            long inPresentationTimeNs = 0;
            long previousPresentationTimeNs = 0;
            SurfaceTexture st = mStManager.getSurfaceTexture();
            mInputFrameCount = 0;
            mOutputFrameCount = 0;
            mDroppedFrameCount = 0;
            mEncodedSize = 0;
            ByteBuffer[] decoderInputBuffers = null;
            if (useDecoder) {
                decoderInputBuffers = mDecoder.getInputBuffers();
            }
            Log.d(TAG, "Start preview");

            while (true) {
                // Acquire a new frame of input, and render it to the Surface.  If we had a
                // GLSurfaceView we could switch EGL contexts and call drawImage() a second
                // time to render it on screen.  The texture can be shared between contexts by
                // passing the GLSurfaceView's EGLContext as eglCreateContext()'s share_context
                // argument.
                //mStManager.awaitNewImage();

                // Wait for available frame periodically pinging the encoder for the output
                while (true) {
                    boolean frameAvailable = mStManager.checkNewImage(2);

                    // check encoder output
                    getEncoderOutput(false);

                    // If encoder generated data submit it to decoder
                    if (useDecoder && mEncoderData != null) {
                        // Get encoder input buffer and fill it with camera data
                        int inputBufIndex = mDecoder.dequeueInputBuffer(1000000);
                        if (inputBufIndex >= 0) {
                            int dataLength = mEncoderData.length;
                            Log.d(TAG, "Decoder input frame # " + mDecoderInputFrameCount + ". TS: " +
                                    (mEncoderDataTimestampUs / 1000) + " ms. Size: " + dataLength);
                            mDecoderFrameInputTimeMs[mDecoderInputFrameCount] =
                                    SystemClock.elapsedRealtime();
                            decoderInputBuffers[inputBufIndex].clear();
                            decoderInputBuffers[inputBufIndex].put(mEncoderData);
                            decoderInputBuffers[inputBufIndex].rewind();
                            mDecoder.queueInputBuffer(inputBufIndex, 0, dataLength,
                                    mEncoderDataTimestampUs, 0);
                            mDecoderInputFrameCount++;
                            if (VP8_MIME == "video/avc" && dataLength < 30) {
                                // Ignore H.264 SPS/PPS NAL
                                mDecoderInputFrameCount--;
                            }
                        }
                        else {
                            Log.e(TAG, "Decoder is not ready: " + inputBufIndex);
                        }
                    }

                    // check decoder output
                    if (useDecoder) {
                        getDecoderOutput(useDecoderSurface, rendererIf);
                    }

                    if (frameAvailable) {
                        break;
                    }
                }

                // First image arrived - this is our reference start time
                if (mInputFrameCount == 0) {
                    startWhen = st.getTimestamp();
                    desiredEnd = startWhen + DURATION_SEC * 1000000000L;
                }

                // Check encoder output.
                //getEncoderOutput(false);

                // Get camera SurfaceTexture timestamp and pass it to encoder surface
                inPresentationTimeNs = st.getTimestamp() - startWhen;
                // Set the presentation time stamp from the SurfaceTexture's time stamp.
                Log.d(TAG, "Input frame # " + mInputFrameCount + ". TS: " +
                        (inPresentationTimeNs / 1000000) + " ms. Duration: " +
                        ((inPresentationTimeNs - previousPresentationTimeNs + 500000) / 1000000) + " ms.");
                mInputSurface.setPresentationTime(inPresentationTimeNs);
                previousPresentationTimeNs = inPresentationTimeNs;

                // Submit it to the encoder.  The eglSwapBuffers call will block if the input
                // is full, which would be bad if it stayed full until we dequeued an output
                // buffer (which we can't do, since we're stuck here).  So long as we fully drain
                // the encoder before supplying additional input, the system guarantees that we
                // can supply another frame without blocking.
                if (mInputFrameCount <= mOutputFrameCount + 1) {
                    mStManager.drawImage();
                    mFrameInputTimeMs[mInputFrameCount] = SystemClock.elapsedRealtime();
                    mInputSurface.swapBuffers();
                    //  Log.d(TAG, "Input frame # " + mInputFrameCount + " submitted.");
                    mInputFrameCount++;
                }
                else {
                    mDroppedFrameCount++;
                    Log.w(TAG, "Encoder is behind - drop frame: " +
                            (mInputFrameCount - mOutputFrameCount));
                }

                // Check encoder output.
                //getEncoderOutput(false);

                // Check the time
                if (System.nanoTime() >= desiredEnd) {
                    break;
                }
            }

            // send end-of-stream to encoder, and drain remaining output
            getEncoderOutput(true);

            // Print some statistics.
            double bitrate = (double)mEncodedSize * 8 * 1e9 / inPresentationTimeNs;
            double fps = mInputFrameCount * 1e9 / inPresentationTimeNs;
            Log.d(TAG, "Encoding duration: " + (inPresentationTimeNs / 1000000) + " ms" +
                    ". Size: " + mEncodedSize + ". Bitrate: " + (int)bitrate + " bps" +
                    ". Fps: " + (int)(fps + 0.5));
            Log.d(TAG, "Encoder Frames In: " + mInputFrameCount + ". Out: " +
                    mOutputFrameCount + ". Dropped: " + mDroppedFrameCount);

            // Get average bitrates and fps.
            String bitrateList = "  Bitrate list: ";
            String fpsList = "  FPS list: ";
            int totalFrameSizePerSecond = 0;
            int framesPerSecond = 0;
            int currentSecond;
            int nextSecond = 0;
            for (int i = 0; i < mOutputFrameCount; i++) {
                currentSecond = (int) (mEncoderPresentationTimeMs[i] / 1000);
                boolean lastFrame = (i == mOutputFrameCount - 1);
                if (!lastFrame) {
                    nextSecond = (int) (mEncoderPresentationTimeMs[i + 1] / 1000);
                }
                totalFrameSizePerSecond += mEncoderFrameOutputSize[i];
                framesPerSecond++;
                if (lastFrame || nextSecond > currentSecond) {
                    int currentBitrate = totalFrameSizePerSecond * 8;
                    bitrateList += (currentBitrate + " ");
                    fpsList += (framesPerSecond + " ");
                    totalFrameSizePerSecond = 0;
                    framesPerSecond = 0;
                }
            }
            Log.d(TAG, bitrateList);
            Log.d(TAG, fpsList);

            // Get average encoding time
            long encodingTime = 0;
            for (int i = 0; i < mOutputFrameCount; i++) {
                encodingTime += (mFrameOutputTimeMs[i] - mFrameInputTimeMs[i]);
            }
            encodingTime /= mOutputFrameCount;
            Log.d(TAG, "Average encoding time: " + encodingTime + " ms.");

            // Get average decoding time
            if (useDecoder) {
                Log.d(TAG, "Decoder Frames In: " + mDecoderInputFrameCount + ". Out: " +
                        mDecoderOutputFrameCount);
                long decodingTime = 0;
                for (int i = 0; i < mDecoderOutputFrameCount; i++) {
                    decodingTime += (mDecoderFrameOutputTimeMs[i] - mDecoderFrameInputTimeMs[i]);
                }
                decodingTime /= mDecoderOutputFrameCount;
                Log.d(TAG, "Average decoding time: " + decodingTime + " ms.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // release everything we grabbed
            releaseCamera();
            releaseEncoder();
            if (useDecoder) {
                releaseDecoder();
            }
            releaseSurfaceTexture();
        }
    }


    /**
     * Configures Camera for video capture.  Sets mCamera.
     * <p>
     * Opens a Camera and sets parameters.  Does not start preview.
     */
    private int prepareCamera(int encWidth, int encHeight, int encFps, int encColorFormat) {
        Log.d(TAG, "Camera " + encWidth + " x " + encHeight +
                ". Color: 0x" + Integer.toHexString(encColorFormat));
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        mCameraFrameDropRatio =
                choosePreviewSizeFps(parms, encWidth, encHeight, encFps, encColorFormat);
        if (parms.isVideoStabilizationSupported()) {
            Log.d(TAG, "Set Video stabilization");
            parms.setVideoStabilization(true);
        }
        /*parms.setWhiteBalance(Parameters.WHITE_BALANCE_AUTO);
        parms.setSceneMode(Parameters.SCENE_MODE_AUTO);
        int index = parms.getExposureCompensation ();
        parms.setExposureCompensation(parms.getMaxExposureCompensation());*/
        // leave the frame rate set to default
        mCamera.setParameters(parms);

        Camera.Size size = parms.getPreviewSize();
        Log.d(TAG, "Camera preview size is " + size.width + "x" + size.height);
        return mCameraFrameDropRatio;
    }

    /**
     * Attempts to find a preview size that matches the provided width and height (which
     * specify the dimensions of the encoded video).  If it fails to find a match it just
     * uses the default preview size.
     */
    private int choosePreviewSizeFps(Camera.Parameters parms,
            int width, int height, int fps, int color) {
        // find best match size
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        if (ppsfv != null) {
            Log.d(TAG, "Camera preferred preview size for video is " +
                    ppsfv.width + "x" + ppsfv.height);
        }

        int bestWidth = 0;
        int bestHeight = 0;
        int maxDiff = Integer.MAX_VALUE;
        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            Log.d(TAG, "Size: " + size.width + " x " + size.height);
            int curDiff = (size.width - width) * (size.width - width) +
                    (size.height - height) * (size.height - height);
            if (curDiff < maxDiff) {
                bestWidth = size.width;
                bestHeight = size.height;
                maxDiff = curDiff;
            }
        }
        Log.d(TAG, "Best match size: " + bestWidth + " x " + bestHeight);
        parms.setPreviewSize(bestWidth, bestHeight);

        // Find best match fps
        int bestFpsRange[] = null;
        maxDiff = Integer.MAX_VALUE;
        List<int[]> supportedFpsRanges = parms.getSupportedPreviewFpsRange();
        for (int[] range : supportedFpsRanges) {
            Log.d(TAG, "Fps: " + range[Parameters.PREVIEW_FPS_MIN_INDEX] + " - " +
                    range[Parameters.PREVIEW_FPS_MAX_INDEX]);
            int curDiff = Math.abs(range[Parameters.PREVIEW_FPS_MIN_INDEX] - fps * 1000) +
                    Math.abs(range[Parameters.PREVIEW_FPS_MAX_INDEX] - fps * 1000);
            if (curDiff < maxDiff) {
                bestFpsRange = range;
                maxDiff = curDiff;
            }
        }
        Log.d(TAG, "Best match fps: " + bestFpsRange[Parameters.PREVIEW_FPS_MIN_INDEX] + " - " +
                bestFpsRange[Parameters.PREVIEW_FPS_MAX_INDEX]);
        parms.setPreviewFpsRange(bestFpsRange[Parameters.PREVIEW_FPS_MIN_INDEX],
                bestFpsRange[Parameters.PREVIEW_FPS_MAX_INDEX]);
        int cameraDropRatio = 0;
        if ((double)bestFpsRange[Parameters.PREVIEW_FPS_MAX_INDEX] / (fps * 1000) > 1.7) {
            cameraDropRatio =
                    (int)((double)bestFpsRange[Parameters.PREVIEW_FPS_MAX_INDEX] / (fps * 1000) + 0.5);
        }
        Log.d(TAG, "Frame drop ratio: " + cameraDropRatio);

        // Find best color
        int format = ImageFormat.NV21;
        if (color == CodecCapabilities.COLOR_FormatYUV420Planar) {
            format = ImageFormat.YV12;
        }
        parms.setPreviewFormat(format);
        return cameraDropRatio;
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        Log.d(TAG, "Releasing camera");
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
            try {
                mCamera.setPreviewTexture(null);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            mCamera.release();
            mCamera = null;
        }
        Log.d(TAG, "Releasing camera done");
    }

    /**
     * Configures SurfaceTexture for camera preview.  Initializes mStManager, and sets the
     * associated SurfaceTexture as the Camera's "preview texture".
     * <p>
     * Configure the EGL surface that will be used for output before calling here.
     */
    private void prepareSurfaceTexture(int frameDropRatio) {
        mStManager = new SurfaceTextureManager(frameDropRatio);
        SurfaceTexture st = mStManager.getSurfaceTexture();
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException("setPreviewTexture failed", ioe);
        }
    }

    /**
     * Releases the SurfaceTexture.
     */
    private void releaseSurfaceTexture() {
        if (mStManager != null) {
            mStManager.release();
            mStManager = null;
        }
    }

    /**
     *  VP8 codec properties generated by getVp8CodecProperties() function.
     */
    protected class CodecProperties {
        CodecProperties(String codecName, int colorFormat) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
        }
        public boolean  isGoogleSwCodec() {
            return codecName.startsWith(OMX_SW_CODEC_PREFIX);
        }

        public final String codecName; // OpenMax component name for VP8 codec.
        public final int colorFormat;  // Color format supported by codec.
    }

    /**
     * Function to find VP8 codec.
     *
     * Iterates through the list of available codecs and tries to find
     * VP8 codec, which can support either YUV420 planar or NV12 color formats.
     * If forceSwGoogleCodec parameter set to true the function always returns
     * Google sw VP8 codec.
     * If forceSwGoogleCodec parameter set to false the functions looks for platform
     * specific VP8 codec first. If no platform specific codec exist, falls back to
     * Google sw VP8 codec.
     *
     * @param isEncoder     Flag if encoder is requested.
     * @param forceSwGoogleCodec  Forces to use Google sw codec.
     */
    protected CodecProperties getVp8CodecProperties(boolean isEncoder, boolean forceSwGoogleCodec) {
        CodecProperties codecProperties = null;

        if (!forceSwGoogleCodec) {
            // Loop through the list of omx components in case platform specific codec
            // is requested.
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (isEncoder != codecInfo.isEncoder()) {
                    continue;
                }
                Log.v(TAG, codecInfo.getName());
                // Check if this is sw Google codec - we should ignore it.
                boolean isGoogleSwCodec = codecInfo.getName().startsWith(OMX_SW_CODEC_PREFIX);
                if (isGoogleSwCodec) {
                    continue;
                }

                for (String type : codecInfo.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(VP8_MIME)) {
                        continue;
                    }
                    CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(VP8_MIME);

                    // Get candidate codec properties.
                    Log.v(TAG, "Found candidate codec " + codecInfo.getName());
                    for (int colorFormat : capabilities.colorFormats) {
                        Log.v(TAG, "   Color: 0x" + Integer.toHexString(colorFormat));
                    }

                    // Check supported color formats.
                    for (int supportedColorFormat : mSupportedColorList) {
                        for (int codecColorFormat : capabilities.colorFormats) {
                            if (codecColorFormat == supportedColorFormat) {
                                codecProperties = new CodecProperties(codecInfo.getName(),
                                        codecColorFormat);
                                Log.d(TAG, "Found target codec " + codecProperties.codecName +
                                        ". Color: 0x" + Integer.toHexString(codecColorFormat));
                                return codecProperties;
                            }
                        }
                    }
                    // HW codec we found does not support one of necessary color formats.
                    throw new RuntimeException("No hw codec with YUV420 or NV12 color formats");
                }
            }
        }
        // If no hw vp8 codec exist or sw codec is requested use default Google sw codec.
        if (codecProperties == null) {
            Log.v(TAG, "Use SW VP8 codec");
            if (isEncoder) {
                codecProperties = new CodecProperties(VPX_SW_ENCODER_NAME,
                        CodecCapabilities.COLOR_FormatYUV420Planar);
            } else {
                codecProperties = new CodecProperties(VPX_SW_DECODER_NAME,
                        CodecCapabilities.COLOR_FormatYUV420Planar);
            }
        }

        return codecProperties;
    }

    /**
     * Configures encoder  and prepares the input Surface.  Initializes
     * mEncoder, mInputSurface, mBufferInfo.
     */
    private void prepareEncoder(int width, int height, int bitRate, boolean useSurface) {
        mBufferInfo = new MediaCodec.BufferInfo();
        CodecProperties properties = getVp8CodecProperties(true, FORCE_SW_CODEC);
        if (useSurface) {
            mEncoderColorFormat = CodecCapabilities.COLOR_FormatSurface;
        } else {
            mEncoderColorFormat = properties.colorFormat;
        }

        Log.d(TAG, "Open encoder " + width + " x " + height + ". @ " + bitRate + " bps.");
        MediaFormat format = MediaFormat.createVideoFormat(VP8_MIME, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger("bitrate-mode", VIDEO_ControlRateConstant); // set CBR
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, mEncoderColorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        Log.d(TAG, "Format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        mEncoder = MediaCodec.createByCodecName(properties.codecName);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        if (useSurface) {
            mEncoderSurface = mEncoder.createInputSurface();
        }
        mEncoder.start();
        ByteBuffer[] encoderInputBuffers = mEncoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        Log.d(TAG, "Input buffers: " + encoderInputBuffers.length +
                ". Output buffers: " + encoderOutputBuffers.length);

        // Output filename.  Ideally this would use Context.getFilesDir() rather than a
        // hard-coded output directory.
        String outputPath = new File(OUTPUT_DIR,
                "camera." + width + "x" + height + ".ivf").toString();
        Log.i(TAG, "Output file is " + outputPath);

        try {
            mIvfWriter = new IvfWriter(outputPath, width, height);
        } catch (IOException e) {
            Log.e(TAG, "IvfWriter failure: " + e.toString());
        }
    }

    /**
     * Releases encoder resources.
     */
    private void releaseEncoder() {
        Log.d(TAG, "Releasing encoder");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mEncoderSurface != null) {
            mEncoderSurface = null;
        }
        if (mIvfWriter != null) {
            try {
                mIvfWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "IvfWriter failure: " + e.toString());
            }
            mIvfWriter = null;
        }
        Log.d(TAG, "Releasing encoder done");
    }


    /**
     * Extracts all pending data from the encoder and writes it to ivf.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once.
     */
    private void getEncoderOutput(boolean endOfStream) {
        //final int TIMEOUT_USEC = 1000;  // 1 ms timeout in dequeue
        final int TIMEOUT_USEC = 0;  // 1 ms timeout in dequeue

        mEncoderData = null;
        if (endOfStream) {
            Log.d(TAG, "Sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet - break from a loop
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    Log.d(TAG, "No output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
                Log.d(TAG, "New output buffers: " + encoderOutputBuffers.length);
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "Encoder output format changed: " + newFormat);
            } else if (encoderStatus < 0) {
                Log.w(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                throw new RuntimeException("encoderOutputBuffer returns error");
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                String logStr = "Encoder output frame # " + mOutputFrameCount +
                        ". TS: " + (mBufferInfo.presentationTimeUs / 1000) + " ms.";
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    logStr += " CONFIG. ";
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0) {
                    logStr += " KEY. ";
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    logStr += " EOS. ";
                }
                logStr += " Size: " + mBufferInfo.size;

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.d(TAG, "Got BUFFER_FLAG_CODEC_CONFIG");
                    //mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    mEncoderData = new byte[mBufferInfo.size];
                    encodedData.position(mBufferInfo.offset);
                    encodedData.get(mEncoderData, 0, mBufferInfo.size);
                    mEncoderDataTimestampUs = mBufferInfo.presentationTimeUs;
                    mFrameOutputTimeMs[mOutputFrameCount] = SystemClock.elapsedRealtime();
                    mEncoderFrameOutputSize[mOutputFrameCount] = mBufferInfo.size;
                    mEncoderPresentationTimeMs[mOutputFrameCount] = mBufferInfo.presentationTimeUs / 1000;
                    logStr += " EncTime: " + (mFrameOutputTimeMs[mOutputFrameCount] -
                            mFrameInputTimeMs[mOutputFrameCount] + " ms.");
                    Log.d(TAG, logStr);

                    if (WRITE_IVF) {
                        try {
                            mIvfWriter.writeFrame(mEncoderData, mBufferInfo.presentationTimeUs);
                        } catch (IOException e) {
                            Log.e(TAG, "IvfWriter failure: " + e.toString());
                        }
                    }
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        mOutputFrameCount++;
                    }
                    mEncodedSize += mBufferInfo.size;
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "Reached end of stream unexpectedly");
                    } else {
                        Log.d(TAG, "EOS reached");
                    }
                    break;      // out of while
                }

                if (mEncoderData != null) {
                    break;
                }
            }
        }
    }

    /**
     * Configures decoder
     */
    private void prepareDecoder(int width, int height, boolean useSurface, Surface screenSurface) {
        CodecProperties properties = getVp8CodecProperties(false, FORCE_SW_CODEC);
        Log.d(TAG, "Open decoder " + width + " x " + height);
        MediaFormat format = MediaFormat.createVideoFormat(VP8_MIME, width, height);
        Log.d(TAG, "Format: " + format);
        Surface decoderSurface = null;
        if (useSurface) {
            decoderSurface = screenSurface;
        }

        mDecoder = MediaCodec.createByCodecName(properties.codecName);
        mDecoder.configure(format, decoderSurface, null, 0);
        mDecoder.start();
        ByteBuffer[] decoderInputBuffers = mDecoder.getInputBuffers();
        ByteBuffer[] decoderOutputBuffers = mDecoder.getOutputBuffers();
        Log.d(TAG, "Input buffers: " + decoderInputBuffers.length +
                ". Output buffers: " + decoderOutputBuffers.length);

    }

    /**
     * Releases decoder resources.
     */
    private void releaseDecoder() {
        Log.d(TAG, "Releasing decoder");
        if (mDecoder != null) {
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
        Log.d(TAG, "Releasing decoder done");
    }


    /**
     * Extracts all pending data from the decoder.
     */
    private void getDecoderOutput(boolean useSurface, VideoRendererIf rendererIf) {
        //final int TIMEOUT_USEC = 1000;  // 1 ms timeout in dequeue
        final int TIMEOUT_USEC = 0;  // 1 ms timeout in dequeue

        ByteBuffer[] decoderOutputBuffers = mDecoder.getOutputBuffers();
        int decoderStatus = mDecoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);

        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return;      // out of while
        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            // not expected for an encoder
            decoderOutputBuffers = mDecoder.getOutputBuffers();
            Log.d(TAG, "New decoder output buffers: " + decoderOutputBuffers.length);
        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // should happen before receiving buffers, and should only happen once
            MediaFormat newFormat = mDecoder.getOutputFormat();
            Log.d(TAG, "Decoder output format changed: " + newFormat);
        } else if (decoderStatus < 0) {
            Log.w(TAG, "Unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
            throw new RuntimeException("encoderOutputBuffer returns error");
        } else {
            ByteBuffer decodedData = decoderOutputBuffers[decoderStatus];
            if (decodedData == null) {
                throw new RuntimeException("decoderOutputBuffer " + decoderStatus + " was null");
            }

            String logStr = "Decoder output frame # " + mDecoderOutputFrameCount +
                    ". TS: " + (mBufferInfo.presentationTimeUs / 1000) + " ms.";
            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                logStr += " CONFIG. ";
            }
            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0) {
                logStr += " KEY. ";
            }
            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                logStr += " EOS. ";
            }
            logStr += " Size: " + mBufferInfo.size;
            Log.d(TAG, logStr);

            mDecoder.releaseOutputBuffer(decoderStatus, useSurface);
            mDecoderFrameOutputTimeMs[mDecoderOutputFrameCount] = SystemClock.elapsedRealtime();
            if (useSurface && rendererIf != null) {
                rendererIf.RenderFrame();
            }

            mDecoderOutputFrameCount++;
        }
    }


    private static class CameraBufferCallback implements PreviewCallback {
        private final int NUM_BUFFERS = 3;
        private Camera mCamera;
        private int mWidth;
        private int mHeight;
        private long mCaptureStartTime = -1;
        private long mLastCaptureTime;
        private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
        private boolean mFrameAvailable;
        private byte[] mCameraData;
        private SurfaceTexture mCameraSurfaceTexture;

        private int mFrameCount;
        private int mDroppedFrameCount;
        private int mFrameDropRatio;

        public CameraBufferCallback(Camera camera, int width, int height, int frameDropRatio) {
            mCamera = camera;
            mWidth = width;
            mHeight = height;
            mFrameDropRatio = frameDropRatio;
        }

        public void release() {
            mCameraSurfaceTexture = null;
        }

        public void prepareCallback(int encoderColorFormat) {
            int format = ImageFormat.NV21;
            int bufSize = mWidth * mHeight * ImageFormat.getBitsPerPixel(format) / 8;
            Log.d(TAG, "bufSize: " + bufSize);

            int[] cameraGlTextures = new int[1];
            // Generate one texture pointer and bind it as an external texture.
            GLES20.glGenTextures(1, cameraGlTextures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraGlTextures[0]);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mCameraSurfaceTexture = new SurfaceTexture(cameraGlTextures[0]);
            mCameraSurfaceTexture.setOnFrameAvailableListener(null);
            try {
                mCamera.setPreviewTexture(mCameraSurfaceTexture);
            } catch (IOException e) {
                Log.e(TAG, "setPreviewTexture error " + e.toString());
            }
            for (int i = 0; i < NUM_BUFFERS; i++) {
                mCamera.addCallbackBuffer(new byte[bufSize]);
            }
            //mCameraData = new byte[bufSize];
            mCamera.setPreviewCallbackWithBuffer(this);
        }

        public long getTimestamp() {
            return (long)mLastCaptureTime * 1000000;  // in ns
        }

        public byte[] getCameraData() {
            return mCameraData;
        }

        public void awaitNewImage() {
            //Log.d(TAG, "awaitNewImage");
            final int TIMEOUT_MS = 2000;

            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            throw new RuntimeException("Camera frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
            }
            //Log.d(TAG, "awaitNewImage done");
        }

        public boolean checkNewImage(int timeoutMs) {
            boolean frameAvailable = false;
            synchronized (mFrameSyncObject) {
                if (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.
                        mFrameSyncObject.wait(timeoutMs);
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                frameAvailable = mFrameAvailable;
                mFrameAvailable = false;
            }
            return frameAvailable;
        }


        public void addCallbackBuffer() {
            synchronized (mFrameSyncObject) {
                mCamera.addCallbackBuffer(mCameraData);
                mCameraData = null;
                mFrameAvailable = false;
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera callbackCamera) {
            //Log.d(TAG, "Camera frame callback thread id = " + Thread.currentThread().getId());
            synchronized (mFrameSyncObject) {
                mFrameCount++;
                if (mFrameDropRatio > 0 && (mFrameCount % mFrameDropRatio) != 0) {
                    // Drop this frame to get target fps
                    mCamera.addCallbackBuffer(data);
                    return;
                }
                if (mFrameAvailable) {
                    Log.w(TAG, "Camera frame dropped!!!");
                    mCamera.addCallbackBuffer(data);
                    mDroppedFrameCount++;
                    return;
                }
                if (mCaptureStartTime < 0) {
                    mCaptureStartTime = SystemClock.elapsedRealtime();
                }
                long captureTime = SystemClock.elapsedRealtime() - mCaptureStartTime;
                float fps = 0;
                if (mFrameCount > 1) {
                    fps = (float)(mFrameCount - 1) * 1000 / captureTime;
                }
                //Log.d(TAG, "Camera frame #" + mFrameCount + ". TS " + captureTime +
                //  ". Duration: " + (captureTime - mLastCaptureTime) + ". Fps: " + fps);
                mLastCaptureTime = captureTime;
                //System.arraycopy(data, 0, mCameraData,  0, data.length);
                mCameraData = data;
                mFrameAvailable = true;
                //mCamera.addCallbackBuffer(data);
                mFrameSyncObject.notifyAll();
            }
        }
    }

    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     * <p>
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     * <p>
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    private static class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

        private Surface mSurface;

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {
            if (surface == null) {
                throw new NullPointerException();
            }
            mSurface = surface;

            eglSetup();
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup() {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mSurface.release();

            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;

            mSurface = null;
        }

        /**
         * Makes our EGL context and surface current.
         */
        public void makeCurrent() {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }


    /**
     * Manages a SurfaceTexture.  Creates SurfaceTexture and TextureRender objects, and provides
     * functions that wait for frames and render them to the current EGL surface.
     * <p>
     * The SurfaceTexture can be passed to Camera.setPreviewTexture() to receive camera output.
     */
    private static class SurfaceTextureManager
            implements SurfaceTexture.OnFrameAvailableListener {
        private SurfaceTexture mSurfaceTexture;
        private TextureRender mTextureRender;

        private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
        private boolean mFrameAvailable;
        private int mFrameCount;
        private int mFrameDropRatio;


        /**
         * Creates instances of TextureRender and SurfaceTexture.
         */
        public SurfaceTextureManager(int frameDropRatio) {
            mFrameDropRatio = frameDropRatio;
            mFrameCount = 0;
            mTextureRender = new TextureRender();
            mTextureRender.surfaceCreated();

            Log.d(TAG, "textureID = " + mTextureRender.getTextureId());
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());

            // This doesn't work if this object is created on the thread that CTS started for
            // these test cases.
            //
            // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
            // create a Handler that uses it.  The "frame available" message is delivered
            // there, but since we're not a Looper-based thread we'll never see it.  For
            // this to do anything useful, OutputSurface must be created on a thread without
            // a Looper, so that SurfaceTexture uses the main application Looper instead.
            //
            // Java language note: passing "this" out of a constructor is generally unwise,
            // but we should be able to get away with it here.
            mSurfaceTexture.setOnFrameAvailableListener(this);
        }

        public void release() {
            // this causes a bunch of warnings that appear harmless but might confuse someone:
            //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
            //mSurfaceTexture.release();

            mTextureRender = null;
            mSurfaceTexture = null;
        }

        /**
         * Returns the SurfaceTexture.
         */
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        /**
         * Latches the next buffer into the texture.  Must be called from the thread that created
         * the OutputSurface object.
         */
        public boolean checkNewImage(int timeoutMs) {
            boolean frameAvailable = false;
            synchronized (mFrameSyncObject) {
                if (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.
                        mFrameSyncObject.wait(timeoutMs);
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                frameAvailable = mFrameAvailable;
                mFrameAvailable = false;
            }
            if (frameAvailable) {
                // Latch the data.
                mTextureRender.checkGlError("before updateTexImage");
                mSurfaceTexture.updateTexImage();

                // Check if we need to drop frame to get target fps
                mFrameCount++;
                if (mFrameDropRatio > 0 && (mFrameCount % mFrameDropRatio) != 0) {
                    frameAvailable = false;
                }
            }
            return frameAvailable;
        }


        /**
         * Latches the next buffer into the texture.  Must be called from the thread that created
         * the OutputSurface object.
         */
        public void awaitNewImage() {
            final int TIMEOUT_MS = 2500;

            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            throw new RuntimeException("Camera frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mFrameAvailable = false;
            }

            // Latch the data.
            mTextureRender.checkGlError("before updateTexImage");
            mSurfaceTexture.updateTexImage();
        }

        /**
         * Draws the data from SurfaceTexture onto the current EGL surface.
         */
        public void drawImage() {
            mTextureRender.drawFrame(mSurfaceTexture);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            //Log.d(TAG, "onFrameAvailable thread id = " + Thread.currentThread().getId());
            synchronized (mFrameSyncObject) {
                if (mFrameAvailable) {
                    throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                }
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }
    }


    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class TextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0, 0.f, 0.f,
                 1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f,  1.0f, 0, 0.f, 1.f,
                 1.0f,  1.0f, 0, 1.f, 1.f,
        };

        private FloatBuffer mTriangleVertices;

        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +      // highp here doesn't seem to matter
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        private float[] mMVPMatrix = new float[16];
        private float[] mSTMatrix = new float[16];

        private int mProgram;
        private int mTextureID = -12345;
        private int muMVPMatrixHandle;
        private int muSTMatrixHandle;
        private int maPositionHandle;
        private int maTextureHandle;

        public TextureRender() {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
        }

        public int getTextureId() {
            return mTextureID;
        }

        public void drawFrame(SurfaceTexture st) {
            checkGlError("onDrawFrame start");
            st.getTransformMatrix(mSTMatrix);

            // (optional) clear to green so we can see if we're failing to set pixels
            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");

            // IMPORTANT: on some devices, if you are sharing the external texture between two
            // contexts, one context may not see updates to the texture unless you un-bind and
            // re-bind it.  If you're not using shared EGL contexts, you don't need to bind
            // texture 0 here.
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkLocation(maPositionHandle, "aPosition");
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            checkLocation(maTextureHandle, "aTextureCoord");

            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            checkLocation(muMVPMatrixHandle, "uMVPMatrix");
            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            checkLocation(muSTMatrixHandle, "uSTMatrix");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);

            mTextureID = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            checkGlError("glBindTexture mTextureID");

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter");
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }

        public static void checkLocation(int location, String label) {
            if (location < 0) {
                throw new RuntimeException("Unable to locate '" + label + "' in program");
            }
        }
    }

}
