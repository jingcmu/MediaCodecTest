package com.example.mediacodectest;

import android.graphics.SurfaceTexture;

public interface VideoRendererIf {
    public void RenderFrame();

    public void RenderFrame(SurfaceTexture surfaceTex);

    public void RenderFrame(int textureId, float[] stMatrix);
}
