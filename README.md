# MediaCodecTest
Encoder speed measurement app
It tries to encode frames from camera in real time.
On Nexus 6 average VP8 HW 720p encoding time - 28 ms, average H.264 encoding time - 19 ms.

To switch between VP8/H.264 comment/uncomment following string in CameraToIvfTest.java:

```
    //private static final String VP8_MIME = "video/x-vnd.on2.vp8";
    private static final String VP8_MIME = "video/avc";
```

This is part of my work in webRTC project in Google.
