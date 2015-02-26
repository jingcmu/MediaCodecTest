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

public class YuvReader {
    private RandomAccessFile mInputFile;
    private int width;
    private int height;
    private int index;

    /**
     * Initializes the YUV file writer.
     *
     * @param filename   name of the YUV file
     * @param width      frame width
     * @param height     frame height
     */
    public YuvReader(String filename, int w, int h, int frameIndex) throws IOException {
    		mInputFile = new RandomAccessFile(filename, "r");
        width = w;
        height = h;
        index = frameIndex;
    }

    /**
     * Close the file.
     */
    public void close() throws IOException{
        mInputFile.close();
    }

    /**
     * Reads a single YUV frame.
     *
     * @param frame     actual contents of the encoded frame data
     */
    public int readFrame(byte[] frame_out, boolean FORCE_SW_CODEC) throws IOException {
    		int sizeOfFrame = (int)(width * height * 1.5);
    		byte[] frame = new byte[sizeOfFrame];
    		int bytes = 0;    		
    		if (FORCE_SW_CODEC) {
    			bytes = mInputFile.read(frame_out, sizeOfFrame * index, sizeOfFrame);
    		} else {
    			bytes = mInputFile.read(frame, sizeOfFrame * index, sizeOfFrame);
    			YUV420ToNV(frame_out, frame);
    		}
    		return bytes;
    }
    
    private void YUV420ToNV(byte[] frame_out, byte[] frame) {
    		System.arraycopy(frame, 0, frame_out, 0, width * height);
    		int u_start = width * height;
    		int v_start = width * height + width * height / 4;
    		for (int i = u_start; i < width * height * 1.5; i += 2) {
    			frame_out[i] = frame[u_start++];
    			frame_out[i+1] = frame[v_start++];
    		}
    }
}
