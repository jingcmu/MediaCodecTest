# MediaCodecTest

### Introduction

This APP is for HW/SW Codec testing on Android devices. 

This App can 
 - encode frames from camera or YUV files in VP8/H.264 using HW/SW encoder
 - output original frames got from camera
 - output encoded frames to an ivf file(for VP8) or an mpeg file(for H.264)

### Test setup

1. Install Android SDK (
   [Link](http://developer.android.com/sdk/installing/installing-adt.html) ) in the Eclipse.
2. Git clone [this project](https://github.com/jingcmu/MediaCodecTest.git).
   Import it to Eclipse: General->Existing Projects to Workspace
3. Connect your device to the computer, run "adb devices" to see whether it is recognized
4. If you want to input from clips, use "adb push ... " to upload the clips to /sdcard,
   Ex: adb push ~/work/video_clips/1.yuv /sdcard/
5. Run the app by right click it and choose "Run as/Android Application", unlock the screen
   of the device and click "Start encoder test with byte buffers".
6. Use "adb pull ..." to download the result to your computer, 
   Ex: adb pull /sdcard/1_0.ivf
7. During the process, you can use "adb shell" to operate the files on the device.

### Test settings

If you want to get ivf result, change the setting in CameraToIvfTest.java;
if you want to get mpeg result, change the setting in CameraToMpegTest.java.
By default, we use Ivf format, if you want to use mp4, change the code in MediaCodecActivity.java.

From:
```
	CameraToIvfTest test = new CameraToIvfTest();
```
To:
```
	CameraToMpegTest test = new CameraToMpegTest();
```

You can specify the resolution, the FPS and the target bitrate in :

```
    private static final int WIDTH = 1280;       // HD
    private static final int HEIGHT = 720;       // HD
    private static final int FRAME_RATE = 30;    // 30fps
    private static final int BITRATE = 2000000;  // 2 Mbps
```

You can switch between VP8/H.264:

```
    //private static final String VP8_MIME = "video/x-vnd.on2.vp8";
    private static final String VP8_MIME = "video/avc";
```

You can decide whether to 
 - output the encoded frames to an IVF file
 - output the original YUV frames to a file
 - output the decoded frames to a file
 - use the input from a clip
 - use SW encoder instead a HW encoder
```
    private static boolean WRITE_IVF = true;
    private static boolean WRITE_YUV = false;
    private static boolean WRITE_DECODED_DATA = true;
    private static boolean USE_CLIP = true;
    private static boolean FORCE_SW_CODEC = false;
```

If you use video clip as input, you can specify the name:
```
    private static final String INPUT_FILE = "the name of the clip without extention";
```

Other setting:
```
    // I Frame interval
    private static final int IFRAME_INTERVAL = 300;
    // Duration for test using camera, for test using clips, the test will also be
    // terminated after this period of time.
    private static final int DURATION_SEC = 300;
```
