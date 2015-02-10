package com.example.mediacodectest;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

public class LooperRunner extends Thread {

    private static final String TAG = "VP8CodecTestBase";

    // Object used to signal that looper thread has started and Handler instance associated
    // with looper thread has been allocated.
    private final Object mThreadEvent = new Object();
    private Handler mHandler;

    public synchronized void requestStart() throws Exception {
        mHandler = null;
        start();
        // Wait for Hander allocation
        synchronized (mThreadEvent) {
            while (mHandler == null) {
                mThreadEvent.wait();
            }
        }
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (mThreadEvent) {
            mHandler = new Handler();
            mThreadEvent.notify();
        }
        Looper.loop();
    }

    public void runCallable(final Callable<?> callable) throws Exception {
        final Exception[] exception = new Exception[1];
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        mHandler.post( new Runnable() {
            @Override
            public void run() {
                try {
                    callable.call();
                } catch (Exception e) {
                    exception[0] = e;
                } finally {
                    countDownLatch.countDown();
                }
            }
        } );

        // Wait for task completion
        countDownLatch.await();
        if (exception[0] != null) {
            throw exception[0];
        }
    }

    public void runCallableNoWait(final Callable<?> callable) {
        mHandler.post( new Runnable() {
            @Override
            public void run() {
                try {
                    callable.call();
                } catch (Exception e) {
                    Log.e(TAG, "Looper exception!!!" + e.toString());
                }
            }
        } );
    }

    public synchronized void requestStop() {
        mHandler.post( new Runnable() {
            @Override
            public void run() {
                // This will run on the Looper thread
                Log.d(TAG, "Looper quitting");
                Looper.myLooper().quitSafely();
            }
        } );
        // Wait for completion
        try {
            join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mHandler = null;
    }
}
