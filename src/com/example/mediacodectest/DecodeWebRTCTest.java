package com.example.mediacodectest;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import org.webrtc.MediaCodecVideoDecoder;
import org.webrtc.VideoRendererGui;
import org.webrtc.MediaCodecVideoDecoder.DecoderOutputBufferInfo;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

public class DecodeWebRTCTest {
    //private static final String ENCODED_IVF_BASE = "football";
    //private static final String INPUT_YUV = "football_qvga.yuv";
    //private static final int WIDTH = 320;
    //private static final int HEIGHT = 240;
    //private static final int BITRATE = 400000;

    private static final String ENCODED_IVF_BASE = "nicklas";
    private static final String INPUT_YUV = "nicklas_720p.yuv";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int BITRATE = 1200000;

    private static final int FPS = 30;
    private static final int DEQUEUE_OUTPUT_TIMEOUT = 500000;  // 500 ms timeout.

    protected static final String SDCARD_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath();

    protected static final String TAG = "VP8CodecTestBase";
    private static final String VP8_MIME = "video/x-vnd.on2.vp8";
    private static final String VPX_SW_DECODER_NAME = "OMX.google.vp8.decoder";
    private static final String VPX_SW_ENCODER_NAME = "OMX.google.vp8.encoder";
    private static final String OMX_SW_CODEC_PREFIX = "OMX.google";
    private static final long DEFAULT_TIMEOUT_INPUT_US = 200000; // 200 ms
    private static final long DEFAULT_TIMEOUT_OUTPUT_US = 200000; // 5 ms;

    // Video bitrate type - should be set to OMX_Video_ControlRateConstant from OMX_Video.h
    protected static final int VIDEO_ControlRateVariable = 1;
    protected static final int VIDEO_ControlRateConstant = 2;

    // NV12 color format supported by QCOM codec, but not declared in MediaCodec -
    // see /hardware/qcom/media/mm-core/inc/OMX_QCOMExtns.h
    private static final int COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m = 0x7FA30C04;
    // Allowable color formats supported by codec - in order of preference.
    private static final int[] mSupportedColorList = {
            CodecCapabilities.COLOR_FormatYUV420Planar,
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
            COLOR_QCOM_FORMATYUV420PackedSemiPlanar32m
    };

    private static final int TEST_R0 = 0;                   // RGB equivalent of {0,0,0} (BT.601)
    private static final int TEST_G0 = 136;
    private static final int TEST_B0 = 0;
    private static final int TEST_R1 = 236;                 // RGB equivalent of {120,160,200} (BT.601)
    private static final int TEST_G1 = 50;
    private static final int TEST_B1 = 186;

    private int mInputFrameIndex;
    private int mOutputFrameIndex;
    private long[] mFrameInputTimeMs = new long[30 * 50];
    private long[] mFrameOutputTimeMs = new long[30 * 50];
    private long[] mFrameSize = new long[30 * 50];

    private LooperRunner mLooperRunner;

    private int mTextureID = -12345;
    private SurfaceTexture mSurfaceTexture = null;
    private Surface mSurface = null;
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

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
    protected CodecProperties getVp8CodecProperties(boolean isEncoder,
            boolean forceSwGoogleCodec) throws Exception {
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
                                Log.v(TAG, "Found target codec " + codecProperties.codecName +
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
     * Converts (de-interleaves) NV12 to YUV420 planar.
     * Stride may be greater than width, slice height may be greater than height.
     */
    private static byte[] NV12ToYUV420(int width, int height,
            int stride, int sliceHeight, byte[] nv12) {
        byte[] yuv = new byte[width * height * 3 / 2];

        // Y plane we just copy.
        for (int i = 0; i < height; i++) {
            System.arraycopy(nv12, i * stride, yuv, i * width, width);
        }

        // U & V plane - de-interleave.
        int u_offset = width * height;
        int v_offset = u_offset + u_offset / 4;
        int nv_offset;
        for (int i = 0; i < height / 2; i++) {
            nv_offset = stride * (sliceHeight + i);
            for (int j = 0; j < width / 2; j++) {
                yuv[u_offset++] = nv12[nv_offset++];
                yuv[v_offset++] = nv12[nv_offset++];
            }
        }
        return yuv;
    }

    // Get average encoding time
    void getAverageCodecTime() {
        long encodingTime = 0;
        long totalSize = 0;
        for (int i = 0; i < mOutputFrameIndex; i++) {
            encodingTime += (mFrameOutputTimeMs[i] - mFrameInputTimeMs[i]);
            totalSize += mFrameSize[i];
        }
        encodingTime /= mOutputFrameIndex;
        Log.d(TAG, "Frames: " + mOutputFrameIndex + ". Codec time: " + encodingTime + " ms." +
                " Size: " + totalSize);
    }


    public void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    private void eglSetup(int width, int height) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // Configure EGL for pbuffer and OpenGL ES 2.0.  We want enough RGB bits
        // to be able to tell if the frame is reasonable.
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
        }

        // Configure context for OpenGL ES 2.0.
        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, 0);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }

        // Create a pbuffer surface.  By using this for output, we can use glReadPixels
        // to test values in the output.
        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        mEGLSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs, 0);
        checkEglError("eglCreatePbufferSurface");
        if (mEGLSurface == null) {
            throw new RuntimeException("surface was null");
        }
    }

    public void makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public void eglRelease() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
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
        mSurfaceTexture = null;
    }

    protected void decode(
            String inputIvfFilename,
            boolean useSurface,
            VideoRenderer.Callbacks renderer) throws Exception {
        Log.d(TAG, "Running decoder on thread id " + Thread.currentThread().getId());
        CodecProperties properties = getVp8CodecProperties(false, false);
        I420Frame yuvVideoFrame;
        I420Frame texVideoFrame;
        int maxFrames = 10 * 30;

        // Open input/output.
        IvfReader ivf = new IvfReader(inputIvfFilename);
        int frameWidth = ivf.getWidth();
        int frameHeight = ivf.getHeight();
        int frameCount = ivf.getFrameCount();
        int frameStride = frameWidth;
        int frameSliceHeight = frameHeight;
        int frameColorFormat = properties.colorFormat;

        // Create surface if necessary
        mSurface = null;
        if (useSurface) {
            // Create EGL context
            eglSetup(frameWidth, frameHeight);
            makeCurrent();

            // Create output surface
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);

            mTextureID = textures[0];
            //GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
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
            Log.d(TAG, "Video decoder TextureID = " + mTextureID);
            mSurfaceTexture = new SurfaceTexture(mTextureID);
            mSurface = new Surface(mSurfaceTexture);
       }


        // Create video frame
        int[] yuvStrides = { frameWidth, frameWidth / 2, frameWidth / 2 };
        yuvVideoFrame = new I420Frame(frameWidth, frameHeight, yuvStrides, null);
        texVideoFrame = new I420Frame(frameWidth, frameHeight, mSurfaceTexture, mTextureID);

        // Create decoder.
        MediaFormat format = MediaFormat.createVideoFormat(VP8_MIME,
                                                           ivf.getWidth(),
                                                           ivf.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, properties.colorFormat);
        Log.d(TAG, "Creating decoder " + properties.codecName +
                ". Color format: 0x" + Integer.toHexString(frameColorFormat) +
                ". " + frameWidth + " x " + frameHeight);
        Log.d(TAG, "  Format: " + format);
        Log.d(TAG, "  In: " + inputIvfFilename);
        MediaCodec decoder = MediaCodec.createByCodecName(properties.codecName);
        decoder.configure(format, mSurface, null, 0);
        decoder.start();

        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        Log.d(TAG, "Input buffers: " + inputBuffers.length +
                ". Output buffers: " + outputBuffers.length);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        // Initialize renderer
        if (renderer != null) {
            renderer.setSize(frameWidth, frameHeight);
        }

        // decode loop
        mInputFrameIndex = 0;
        mOutputFrameIndex = 0;
        long inPresentationTimeUs = 0;
        long outPresentationTimeUs = 0;
        boolean sawOutputEOS = false;
        boolean sawInputEOS = false;

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                int inputBufIndex = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_INPUT_US);
                if (inputBufIndex >= 0) {
                    byte[] frame = ivf.readFrame(mInputFrameIndex);
                    inPresentationTimeUs = (long)(ivf.getFrameTimestamp(mInputFrameIndex) * 1e6);

                    if ((mInputFrameIndex == frameCount - 1) || (mInputFrameIndex == maxFrames - 1)) {
                        Log.d(TAG, "  Input EOS for frame # " + mInputFrameIndex);
                        sawInputEOS = true;
                    }
                    Log.d(TAG, "Decoder input frame # " + mInputFrameIndex + ". TS: " +
                            (inPresentationTimeUs / 1000) + " ms. Size: " + frame.length);
                    mFrameInputTimeMs[mInputFrameIndex] = SystemClock.elapsedRealtime();
                    inputBuffers[inputBufIndex].clear();
                    inputBuffers[inputBufIndex].put(frame);
                    inputBuffers[inputBufIndex].rewind();

                    decoder.queueInputBuffer(
                            inputBufIndex,
                            0,  // offset
                            frame.length,
                            inPresentationTimeUs,
                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                    mInputFrameIndex++;
                }
            }

            int result = decoder.dequeueOutputBuffer(bufferInfo, DEFAULT_TIMEOUT_OUTPUT_US);
            while (result == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED ||
                    result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (result == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                } else  if (result == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Process format change
                    format = decoder.getOutputFormat();
                    frameWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    frameHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    frameColorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    Log.d(TAG, "Decoder output format change. Color: 0x" +
                            Integer.toHexString(frameColorFormat));
                    Log.d(TAG, "Format: " + format.toString());

                    // Parse frame and slice height from undocumented values
                    if (format.containsKey("stride")) {
                        frameStride = format.getInteger("stride");
                    } else {
                        frameStride = frameWidth;
                    }
                    if (format.containsKey("slice-height")) {
                        frameSliceHeight = format.getInteger("slice-height");
                    } else {
                        frameSliceHeight = frameHeight;
                    }
                    Log.d(TAG, "Frame stride and slice height: " + frameStride +
                            " x " + frameSliceHeight);
                }
                result = decoder.dequeueOutputBuffer(bufferInfo, DEFAULT_TIMEOUT_OUTPUT_US);
            }
            if (result >= 0) {
                int outputBufIndex = result;
                outPresentationTimeUs = bufferInfo.presentationTimeUs;
                Log.d(TAG, "Decoder output frame # " + mOutputFrameIndex +
                        ". TS: " + (outPresentationTimeUs / 1000) + " ms. Size: " + bufferInfo.size);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                    Log.d(TAG, "   Output EOS for frame # " + mOutputFrameIndex);
                }

                if (bufferInfo.size > 0 && !useSurface) {
                    // Save decoder output to yuv file.
                    byte[] frame = new byte[bufferInfo.size];
                    outputBuffers[outputBufIndex].position(bufferInfo.offset);
                    outputBuffers[outputBufIndex].get(frame, 0, bufferInfo.size);
                    // Convert NV12 to YUV420 if necessary
                    if (frameColorFormat != CodecCapabilities.COLOR_FormatYUV420Planar) {
                        frame = NV12ToYUV420(frameWidth, frameHeight,
                                frameStride, frameSliceHeight, frame);
                    }
                    yuvVideoFrame.copyFrom(frame);
                }
                decoder.releaseOutputBuffer(outputBufIndex, useSurface);
                mFrameOutputTimeMs[mOutputFrameIndex] = SystemClock.elapsedRealtime();
                if (bufferInfo.size > 0 && renderer != null) {
                    if (useSurface) {
                        renderer.renderFrame(texVideoFrame);
                    } else {
                        renderer.renderFrame(yuvVideoFrame);
                    }
                }
                if (bufferInfo.size > 0) {
                    mOutputFrameIndex++;
                }
            }
            if (result == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.v(TAG, "INFO_TRY_AGAIN_LATER");
            }
        }
        decoder.stop();
        decoder.release();
        ivf.close();
        if (useSurface) {
            eglRelease();
        }
    }

    protected void decodeWebRTC(
        String inputIvfFilename,
        boolean useSurface,
        EGLContext sharedContext,
        VideoRenderer.Callbacks renderer) throws Exception {
        Log.d(TAG, "Running decoder on thread id " + Thread.currentThread().getId());
        CodecProperties properties = getVp8CodecProperties(false, false);
        I420Frame yuvVideoFrame;
        I420Frame texVideoFrame;
        int maxFrames = 10 * 30;
        //int maxFrames = 15;

        // Open input/output.
        IvfReader ivf = new IvfReader(inputIvfFilename);
        int frameWidth = ivf.getWidth();
        int frameHeight = ivf.getHeight();
        int frameCount = ivf.getFrameCount();
        int frameStride = frameWidth;
        int frameSliceHeight = frameHeight;
        int frameColorFormat = properties.colorFormat;

        // Create decoder.
        MediaCodecVideoDecoder decoder = new MediaCodecVideoDecoder();
        decoder.initDecode(frameWidth, frameHeight, false, useSurface, sharedContext);

        // Create video frame
        int[] yuvStrides = { frameWidth, frameWidth / 2, frameWidth / 2 };
        yuvVideoFrame = new I420Frame(frameWidth, frameHeight, yuvStrides, null);
        texVideoFrame = new I420Frame(frameWidth, frameHeight, decoder.surfaceTexture, decoder.textureID);

        // Initialize renderer
        if (renderer != null) {
            renderer.setSize(frameWidth, frameHeight);
        }

        // decode loop
        mInputFrameIndex = 0;
        mOutputFrameIndex = 0;
        long inPresentationTimeUs = 0;
        //long outPresentationTimeUs = 0;
        boolean sawEOS = false;

        while (!sawEOS) {
            if (!sawEOS) {
                int inputBufIndex = decoder.dequeueInputBuffer();
                if (inputBufIndex >= 0) {
                    byte[] frame = ivf.readFrame(mInputFrameIndex);
                    inPresentationTimeUs = (long)(ivf.getFrameTimestamp(mInputFrameIndex) * 1e6);

                    if ((mInputFrameIndex == frameCount - 1) || (mInputFrameIndex == maxFrames - 1)) {
                        Log.d(TAG, "  Input EOS for frame # " + mInputFrameIndex);
                        sawEOS = true;
                    }
                    Log.d(TAG, "Decoder input frame # " + mInputFrameIndex + ". TS: " +
                            (inPresentationTimeUs / 1000) + " ms. Size: " + frame.length);
                    mFrameInputTimeMs[mInputFrameIndex] = SystemClock.elapsedRealtime();
                    decoder.inputBuffers[inputBufIndex].clear();
                    decoder.inputBuffers[inputBufIndex].put(frame);
                    decoder.inputBuffers[inputBufIndex].rewind();

                    decoder.queueInputBuffer(
                            inputBufIndex,
                            frame.length,
                            inPresentationTimeUs);

                    mInputFrameIndex++;
                }
            }

            DecoderOutputBufferInfo output = decoder.dequeueOutputBuffer(DEQUEUE_OUTPUT_TIMEOUT);
            if (output != null && output.index >= 0) {
                int outputBufIndex = output.index;
                Log.d(TAG, "Decoder output frame # " + mOutputFrameIndex +
                        ". TS: " + (output.presentationTimestampUs / 1000) +
                        " ms. Size: " + output.size);

                if (!useSurface) {
                    // Save decoder output to yuv file.
                    byte[] frame = new byte[output.size];
                    decoder.outputBuffers[outputBufIndex].position(output.offset);
                    decoder.outputBuffers[outputBufIndex].get(frame, 0, output.size);
                    // Convert NV12 to YUV420 if necessary
                    if (frameColorFormat != CodecCapabilities.COLOR_FormatYUV420Planar) {
                        frame = NV12ToYUV420(frameWidth, frameHeight,
                                frameStride, frameSliceHeight, frame);
                    }
                    yuvVideoFrame.copyFrom(frame);
                }
                decoder.releaseOutputBuffer(outputBufIndex, useSurface);
                mFrameOutputTimeMs[mOutputFrameIndex] = SystemClock.elapsedRealtime();
                if (renderer != null) {
                    if (useSurface) {
                        renderer.renderFrame(texVideoFrame);
                    } else {
                        renderer.renderFrame(yuvVideoFrame);
                    }
                }
                //Thread.sleep(100);
                mOutputFrameIndex++;
            }
            if (output == null) {
                Log.v(TAG, "INFO_TRY_AGAIN_LATER");
            }
            if (output != null && output.index == -1) {
                Log.e(TAG, "Decoder Error!!!");
            }
        }
        decoder.release();
        ivf.close();
/*
        if (useSurface) {
          for (int i = 0; i < maxFrames; i++) {
              Log.d(TAG, "Render frame # " + i);
              renderer.renderFrame(texVideoFrame);
              Thread.sleep(500);
          }
        }*/

    }


    public void testDecoder(
        final  VideoRenderer.Callbacks renderer,
        final boolean useSurface,
        final EGLContext sharedContext) throws Exception {

        final String encodedIvfFilename = SDCARD_DIR + File.separator + ENCODED_IVF_BASE +
                "_" + WIDTH + "x" + HEIGHT + ".ivf";
        final String outputYuvFilename = null;

        Log.d(TAG, "---------- testSurfaceBasic on thread id: " + Thread.currentThread().getId());
        mLooperRunner = new LooperRunner();
        mLooperRunner.requestStart();
        // Configure and open camera on looper thread
        mLooperRunner.runCallableNoWait( new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                //decode(encodedIvfFilename, useSurface, renderer);
                decodeWebRTC(encodedIvfFilename, useSurface, sharedContext, renderer);
                getAverageCodecTime();
                //VideoRendererGui.printStatistics();
                return null;
            }
        } );

    }
}
