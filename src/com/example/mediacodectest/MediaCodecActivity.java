
package com.example.mediacodectest;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.RelativeLayout;

import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;
import org.webrtc.VideoRendererGui.ScalingType;

import java.util.concurrent.CountDownLatch;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MediaCodecActivity extends Activity implements SurfaceHolder.Callback {
    protected static final String TAG = "VP8CodecTestBase";

    private RelativeLayout mLayout;
    private Button buttonStartSurface;
    private Button buttonStartBuffer;
    private Button buttonStartDecoder;
    private SurfaceView sv;
    private GLSurfaceView glSv;
    private MyGLRenderer mRenderer;
    private VideoTextureRender mTextureRender;
    private float[] mStMatrix;
    private VideoRenderer.Callbacks localRenderer;
    private VideoRenderer.Callbacks remoteRenderer;
    private boolean useGlSurfaceView = true;
   // Set to false for camera encoding testing, true for WebRTC classes testing
    private boolean useVideoRendererGui = false;

    private class MyGLRenderer implements GLSurfaceView.Renderer, VideoRendererIf {
        private int mDrawCount = 0;
        private GLSurfaceView glSv;
        private Object mDrawSyncObject = new Object();     // guards mFrameAvailable
        private boolean mDrawDone = false;
        private boolean mFrameArrived = false;
        private int mTextureID = -1;
        public EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;


        public MyGLRenderer(GLSurfaceView glSurfaceVeiw) {
            Log.d(TAG, "MyGLRenderer ctor");
            glSv = glSurfaceVeiw;
            glSv.setPreserveEGLContextOnPause(true);
            glSv.setEGLContextClientVersion(2);
            glSv.setRenderer(this);
            glSv.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }

        public Surface getSurface() {
            return mSurface;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            Log.d(TAG, "GL onSurfaceCreated - GL thred id " + Thread.currentThread().getId());
            mEGLContext = EGL14.eglGetCurrentContext();
            GLES20.glClearColor(0.0f, 0.0f, 0.4f, 1.0f);
            mTextureRender = new VideoTextureRender();
            mTextureRender.initialize();

            // a) Enable this for camera decoding testing -
            // The surface is created by GLSurfaceView. Decoder is supplied with this surface
            // at init time and then calls RenderFrame() to render this surface
            // b) Disable this for pure video decoding testing. Surface is created by decoder itself
            // and this mSurfaceTexture local variable is assigned in
            // RenderFrame(SurfaceTexture surfaceTexture)
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureID());
            mSurface = new Surface(mSurfaceTexture);

            mFrameArrived = false;
            mDrawDone = false;
            Log.d(TAG, "GL onSurfaceCreated done");
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            Log.d(TAG, "GL onSurfaceChanged: " + width + " x " + height);
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            // Redraw background color
            Log.d(TAG, "GL onDrawFrame - thread ID " + Thread.currentThread().getId());
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

/*            if (mFrameArrived && mSurfaceAttached) {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(mSTMatrix);
                mTextureRender.drawFrame(mSTMatrix, textureID);
                Log.d(TAG, "drawImage done: " + mDrawCount);
                mDrawCount++;
                mFrameArrived = false;
            } */

            if (mFrameArrived && mTextureID == -1) {
                mSurfaceTexture.updateTexImage();
                mTextureRender.drawFrame(mSurfaceTexture);
                //Log.d(TAG, "drawImage done: " + mDrawCount);
                mDrawCount++;
                mFrameArrived = false;
            }
            else if (mFrameArrived && mTextureID > 0) {
                mTextureRender.drawFrame(mTextureID, mStMatrix);
                //Log.d(TAG, "drawImage done: " + mDrawCount);
                mDrawCount++;
                mFrameArrived = false;
            }

            synchronized (mDrawSyncObject) {
                mDrawDone = true;
                mDrawSyncObject.notifyAll();
            }
        }

        @Override
        public void RenderFrame() {
            final int TIMEOUT_MS = 500;
            //Log.d(TAG, "RenderFrame ");
            mFrameArrived = true;
            mDrawDone = false;
            glSv.requestRender();

            synchronized (mDrawSyncObject) {
                while (!mDrawDone) {
                    try {
                        mDrawSyncObject.wait(TIMEOUT_MS);
                        if (!mDrawDone) {
                            throw new RuntimeException("Draw frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mDrawDone = false;
            }
            //Log.d(TAG, "RenderFrame done");
        }

        @Override
        public void RenderFrame(SurfaceTexture surfaceTexture) {
            final int TIMEOUT_MS = 500;
            Log.d(TAG, "RenderFrame - surfaceTexture");
            mSurfaceTexture = surfaceTexture;
            mFrameArrived = true;

            synchronized (mDrawSyncObject) {
                mDrawDone = false;
                glSv.requestRender();
                while (!mDrawDone) {
                    try {
                        mDrawSyncObject.wait(TIMEOUT_MS);
                        if (!mDrawDone) {
                            throw new RuntimeException("Draw frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mDrawDone = false;
            }
            //Log.d(TAG, "RenderFrame done");
        }

        @Override
        public void RenderFrame(int textureId, float[] stMatrix) {
            final int TIMEOUT_MS = 500;
            Log.d(TAG, "RenderFrame - textureID " + textureId);
            mFrameArrived = true;
            mTextureID = textureId;
            mStMatrix = stMatrix;

            synchronized (mDrawSyncObject) {
                mDrawDone = false;
                glSv.requestRender();
                while (!mDrawDone) {
                    try {
                        mDrawSyncObject.wait(TIMEOUT_MS);
                        if (!mDrawDone) {
                            throw new RuntimeException("Draw frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mDrawDone = false;
            }
            //Log.d(TAG, "RenderFrame done");
        }


    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Model: " + Build.MODEL);

        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mLayout = new RelativeLayout(this);
        //LinearLayout layout = new LinearLayout(this);
        //layout.setOrientation(LinearLayout.VERTICAL);


        if (useGlSurfaceView) {
            glSv = new GLSurfaceView(this);
            if (useVideoRendererGui) {
                VideoRendererGui.setView(glSv);
                localRenderer = VideoRendererGui.createGuiRenderer(0, 0, 100, 100, ScalingType.SCALE_ASPECT_FIT);
                //localRenderer = VideoRendererGui.createGuiRenderer(0, 0, 50, 100, ScalingType.SCALE_ASPECT_FIT);
                //localRenderer = VideoRendererGui.createGuiRenderer(0, 0, 100, 30, ScalingType.SCALE_ASPECT_FILL);
                //localRenderer = VideoRendererGui.createGuiRenderer(5, 5, 90, 90);
                //localRenderer = VideoRendererGui.createGuiRenderer(5, 5, 40, 90);
                remoteRenderer = VideoRendererGui.createGuiRenderer(50, 0, 50, 100, ScalingType.SCALE_ASPECT_FIT);
            } else {
                mRenderer = new MyGLRenderer(glSv);
            }

            mLayout.addView(glSv);
        }
        else {
            sv = new SurfaceView(this);
            sv.getHolder().addCallback(this);
            mLayout.addView(sv);
        }

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        buttonStartSurface = new Button(this);
        buttonStartSurface.setText("Start encoder test with surfaces.");
        buttonStartSurface.setLayoutParams(lp);
        buttonStartSurface.setBackgroundColor(0x80E0E0E0);
        buttonStartSurface.setOnClickListener(mStartSurfaceCameraListener);
        mLayout.addView(buttonStartSurface, lp);

        lp = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        buttonStartBuffer = new Button(this);
        buttonStartBuffer.setText("Start encoder test with byte buffers.");
        buttonStartBuffer.setLayoutParams(lp);
        buttonStartBuffer.setBackgroundColor(0x80E0E0E0);
        buttonStartBuffer.setOnClickListener(mStartBufferCameraListener);
        mLayout.addView(buttonStartBuffer, lp);

        lp = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        buttonStartDecoder = new Button(this);
        buttonStartDecoder.setText("Start decoder test with surface.");
        buttonStartDecoder.setLayoutParams(lp);
        buttonStartDecoder.setBackgroundColor(0x80E0E0E0);
        //buttonStartDecoder.setOnClickListener(mStartDecodeSurfaceListener);
        buttonStartDecoder.setOnClickListener(mStartDecodeWebRTCListener);
        mLayout.addView(buttonStartDecoder, lp);

        setContentView(mLayout);
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        glSv.onPause();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        glSv.onResume();
    }
/*
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged");
        mRenderer.onSurfaceSoonDestroyed();
        super.onConfigurationChanged(newConfig);
        setContentView(mLayout);
    }
*/
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: " + width + " x " + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
    }


    private OnClickListener mStartSurfaceCameraListener = new OnClickListener() {
        public void onClick(View v) {
            Log.d(TAG, "Start surface test. GUI thread id = " + Thread.currentThread().getId());
            CameraToIvfTest test = new CameraToIvfTest();
            try {
                if (useGlSurfaceView) {
                    test.testEncodeSurfaceCameraToIvf(mRenderer.getSurface(), mRenderer);
                } else {
                    test.testEncodeSurfaceCameraToIvf(sv.getHolder().getSurface(), null);
                }
            } catch (Throwable e) {
                Log.e(TAG, e.toString());
            }
            Log.d(TAG, "Surface test done");
        }
    };

    private OnClickListener mStartBufferCameraListener = new OnClickListener() {
        public void onClick(View v) {
            Log.d(TAG, "Start byte buffer test. GUI thread id = " + Thread.currentThread().getId());
            CameraToIvfTest test = new CameraToIvfTest();
            try {
                if (useGlSurfaceView) {
                    test.testEncodeBufferCameraToIvf(mRenderer.getSurface(), mRenderer);
                } else {
                    test.testEncodeBufferCameraToIvf(sv.getHolder().getSurface(), null);
                }
            } catch (Throwable e) {
                Log.e(TAG, e.toString());
            }
            Log.d(TAG, "Byte buffer test done");
        }
    };

    private OnClickListener mStartDecodeSurfaceListener = new OnClickListener() {
        public void onClick(View v) {
            Log.d(TAG, "Start decoder surface test. GUI thread id = " + Thread.currentThread().getId());
            DecodeSurfaceTest test = new DecodeSurfaceTest();
            try {
                if (useGlSurfaceView) {
                    //test.testDecoder(null, mRenderer, mRenderer.mEGLContext);
                    test.testDecoder(mRenderer.getSurface(), mRenderer, mRenderer.mEGLContext);
                } else {
                    test.testDecoder(sv.getHolder().getSurface(), null, mRenderer.mEGLContext);
                }
            } catch (Throwable e) {
                Log.e(TAG, e.toString());
            }
            Log.d(TAG, "Byte buffer test done");
        }
    };

    private OnClickListener mStartDecodeWebRTCListener = new OnClickListener() {
        public void onClick(View v) {
            boolean useSurfaceLocal = true;
            boolean useSurfaceRemote = false;
            Log.d(TAG, "Start decoder WebRTC test. GUI thread id = " + Thread.currentThread().getId());
            DecodeWebRTCTest testLocal = new DecodeWebRTCTest();
            //DecodeWebRTCTest testRemote = new DecodeWebRTCTest();
            try {
                testLocal.testDecoder(localRenderer, useSurfaceLocal, VideoRendererGui.getEGLContext());
                //testRemote.testDecoder(remoteRenderer, useSurfaceRemote, VideoRendererGui.getEGLContext());
            } catch (Throwable e) {
                Log.e(TAG, e.toString());
            }
            Log.d(TAG, "Decoder WebRTC test done");
        }
    };

}

