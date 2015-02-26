/*
 * Copyright (C) 2015 The Android Open Source Project
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

import java.io.IOException;
import java.io.RandomAccessFile;

public class YuvWriter {
    private RandomAccessFile mOutputFile;
    private int width;
    private int height;

    /**
     * Initializes the YUV file writer.
     *
     * @param filename   name of the YUV file
     * @param width      frame width
     * @param height     frame height
     */
    public YuvWriter(String filename, int w, int h) throws IOException {
        mOutputFile = new RandomAccessFile(filename, "rw");
        mOutputFile.setLength(0);
        width = w;
        height = h;
    }

    /**
     * Close the file.
     */
    public void close() throws IOException{
        mOutputFile.close();
    }

    /**
     * Writes a single YUV frame.
     *
     * @param frame     actual contents of the encoded frame data
     */
    public void writeFrame(byte[] frame, boolean FORCE_SW_CODEC) throws IOException {
    		byte[] new_frame = new byte[(int)(width * height * 1.5)];
    		if (FORCE_SW_CODEC) {
    			mOutputFile.write(frame);
    		} else {
    			NVToYUV420(frame, new_frame);
    			mOutputFile.write(new_frame);
    		}
    }
    
    private void NVToYUV420(byte[] frame, byte[] new_frame) {
    		System.arraycopy(frame, 0, new_frame, 0, width * height);
    		int u_start = width * height;
    		int v_start = width * height + width * height / 4;
    		for (int i = u_start; i < width * height * 1.5; i += 2) {
    			new_frame[u_start++] = frame[i];
    			new_frame[v_start++] = frame[i+1];
    		}
    }
}
