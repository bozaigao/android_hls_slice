package com.github.crazyorr.ffmpegrecorder;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.github.crazyorr.ffmpegrecorder.data.RecordFragment;
import com.github.crazyorr.ffmpegrecorder.data.RecordedFrame;
import com.github.crazyorr.ffmpegrecorder.function.DaggerDependencyModule;
import com.github.crazyorr.ffmpegrecorder.function.FFmpegFrameRecorderSlice;
import com.github.crazyorr.ffmpegrecorder.util.CameraHelper;
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.LoadBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegNotSupportedException;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.inject.Inject;
import butterknife.Bind;
import butterknife.ButterKnife;
import dagger.ObjectGraph;
import static java.lang.Thread.State.WAITING;

public class FFmpegRecordActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener, View.OnClickListener {
    private static final String LOG_TAG = FFmpegRecordActivity.class.getSimpleName();

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    //实时直播时间限制为Long无穷大
    private static final long MAX_VIDEO_LENGTH = Long.MAX_VALUE;
    @Bind(R.id.camera_preview)
    FixedRatioCroppedTextureView mPreview;
    @Bind(R.id.btn_start)
    Button mBtnStart;
    @Bind(R.id.btn_stop)
    Button mBtnStop;
    @Bind(R.id.btn_switch_camera)
    Button mBtnSwitchCamera;
    @Bind(R.id.btn_local_slice)
    Button localSliceBT;
    @Inject
    FFmpeg ffmpeg;
    private int mCameraId;
    private Camera mCamera;
    private FFmpegFrameRecorderSlice mFrameRecorder;
    private VideoRecordThread mVideoRecordThread;
    private AudioRecord mAudioRecord;
    private AudioRecordThread mAudioRecordThread;
    private volatile boolean mRecording = false;
    private File mVideo;
    private LinkedBlockingQueue<RecordedFrame> mRecordedFrameQueue;
    private ConcurrentLinkedQueue<RecordedFrame> mRecycledFrameQueue;
    private int mRecordedFrameCount;
    private int mProcessedFrameCount;
    private long mTotalProcessFrameTime;
    private Stack<RecordFragment> mRecordFragments;

    private int sampleAudioRateInHz = 44100;
    /* The sides of width and height are based on camera orientation.
    That is, the preview size is the size before it is rotated. */
    private int previewWidth = PREFERRED_PREVIEW_WIDTH;
    private int previewHeight = PREFERRED_PREVIEW_HEIGHT;
    // Output video size
    private int videoWidth = 320;
    private int videoHeight = 240;
    private int frameRate = 30;
    private int frameDepth = Frame.DEPTH_UBYTE;
    private int frameChannels = 2;
    private static final int VIDEO_REQUEST_CODE = 4;
    private AlertDialog.Builder builder;
    private int meesageTypeIndex = 0;//消息类型游标

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffmpeg_record);
        ButterKnife.bind(this);
        //初始化ffmpeg命令执行环境
        ObjectGraph.create(new DaggerDependencyModule(this)).inject(this);
        //将ffmpeg加载到内存作为执行命令环境
        loadFFMpegBinary();
        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        // Switch width and height
        mPreview.setPreviewSize(previewHeight, previewWidth);
        mPreview.setCroppedSizeWeight(videoWidth, videoHeight);
        mPreview.setSurfaceTextureListener(this);
        mBtnStart.setOnClickListener(this);
        mBtnStop.setOnClickListener(this);
        mBtnSwitchCamera.setOnClickListener(this);
        localSliceBT.setOnClickListener(this);
        mPreview.setOnClickListener(this);
    }


    private void loadFFMpegBinary() {
        try {
            ffmpeg.loadBinary(new LoadBinaryResponseHandler() {
                @Override
                public void onFailure() {
                    showUnsupportedExceptionDialog();
                }
            });
        } catch (FFmpegNotSupportedException e) {
            showUnsupportedExceptionDialog();
        }
    }

    /**
     * 执行切片命令函数 Created by 波仔糕 on ${DATE}.
     */
    private void execFFmpegBinary(final String[] command) {
        try {
            final ProgressDialog mProgressDialog = ProgressDialog.show(FFmpegRecordActivity.this,
                    null, getString(R.string.processing), true);
            ffmpeg.execute(command, new ExecuteBinaryResponseHandler() {
                @Override
                public void onFailure(String s) {
                    Toast.makeText(FFmpegRecordActivity.this, "FAILED with output : " + s, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onSuccess(String s) {
                }

                @Override
                public void onProgress(String s) {
                }

                @Override
                public void onStart() {
                    mProgressDialog.setMessage("处理中...");
                    mProgressDialog.show();
                }

                @Override
                public void onFinish() {
                    mProgressDialog.dismiss();
                    Toast.makeText(FFmpegRecordActivity.this, "切片存放路径:root/local_media_slice", Toast.LENGTH_LONG).show();
                }
            });
        } catch (FFmpegCommandAlreadyRunningException e) {
        }
    }

    private void showUnsupportedExceptionDialog() {
        new AlertDialog.Builder(FFmpegRecordActivity.this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("你的设备不支持")
                .setMessage("ffmpeg对你的设备不支持")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        FFmpegRecordActivity.this.finish();
                    }
                })
                .create()
                .show();

    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecorder(true);
        releaseRecorder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //手机唯一设备号
        TelephonyManager mTm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        final String imei = mTm.getDeviceId();
        final long time_stamps = SystemClock.currentThreadTimeMillis();
        //实时流切片保证每台设备文件名不一样，这样上传路由器不会导致文件覆盖
        FFmpegFrameRecorderSlice.M3U8_FILE_NAME = imei+"-"+time_stamps+".m3u8";
        FFmpegFrameRecorderSlice.OUTPUT_PREFIX = imei+"-"+time_stamps;
        acquireCamera();
        SurfaceTexture surfaceTexture = mPreview.getSurfaceTexture();
        if (surfaceTexture != null) {
            startPreview(surfaceTexture);
            if (mFrameRecorder == null) {
                new Thread() {
                    @Override
                    public void run() {
                        initRecorder();
                        startRecorder();
                        startRecording();
                    }
                }.start();
            } else {
                startRecording();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseRecording();
        stopRecording();
        stopPreview();
        releaseCamera();
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, int width, int height) {
        new Thread() {
            @Override
            public void run() {
                initRecorder();
                startPreview(surface);
                startRecorder();
                startRecording();
            }
        }.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.camera_preview:
                mCamera.autoFocus(null);
                break;
            case R.id.btn_local_slice:
                startVideo();
                break;
            case R.id.btn_start:
                final String[] messageTypes;
                //领主或者捕快
                messageTypes = new String[3];
                messageTypes[0] = "5秒";
                messageTypes[1] = "10秒";
                messageTypes[2] = "15秒";
                builder = new AlertDialog.Builder(this);
                builder.setTitle("选择切片时间")
                        .setSingleChoiceItems(messageTypes, meesageTypeIndex, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                meesageTypeIndex = which;
                            }
                        });
                builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(FFmpegRecordActivity.this,"开始实时切片",Toast.LENGTH_LONG).show();
                        switch (meesageTypeIndex) {
                            case 0:
                                FFmpegFrameRecorderSlice.SEGMENT_DURATION = 5;
                                resumeRecording();
                                mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                                Log.e("TAG","5秒");
                                break;
                            case 1:
                                FFmpegFrameRecorderSlice.SEGMENT_DURATION = 10;
                                resumeRecording();
                                mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                                Log.e("TAG", "10秒");
                                break;
                            case 2:
                                FFmpegFrameRecorderSlice.SEGMENT_DURATION = 15;
                                resumeRecording();
                                mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                                Log.e("TAG", "15秒");
                                break;

                        }
                        dialog.dismiss();
                    }
                });
                builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                builder.show();
                break;
            case R.id.btn_stop:
                pauseRecording();
                // check video length
                if (calculateTotalRecordedTime(mRecordFragments) < MIN_VIDEO_LENGTH) {
                    Toast.makeText(this, R.string.video_too_short, Toast.LENGTH_SHORT).show();
                    return;
                }
                new FinishRecordingTask().execute();
                mBtnSwitchCamera.setVisibility(View.VISIBLE);
                break;
            case R.id.btn_switch_camera:
                new Thread() {
                    @Override
                    public void run() {
                        stopRecording();
                        stopPreview();
                        releaseCamera();
                        mCameraId = (mCameraId + 1) % 2;
                        acquireCamera();
                        startPreview(mPreview.getSurfaceTexture());
                        startRecording();
                    }
                }.start();
                break;
        }
    }

    // 选择视频
    protected void startVideo() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "请选择切片视频"), VIDEO_REQUEST_CODE);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            //选择视频
            case VIDEO_REQUEST_CODE:
                if (data != null) {
                    // 得到视频的全路径
                    Uri uri = data.getData();
                    final String path = getRealFilePath(FFmpegRecordActivity.this, uri);
                    final String media_name = path.substring(path.lastIndexOf("/") + 1, path.lastIndexOf("."));
                    //手机唯一设备号
                    TelephonyManager mTm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                    final String imei = mTm.getDeviceId();
                    final String[] messageTypes;
                    //领主或者捕快
                    messageTypes = new String[3];
                    messageTypes[0] = "5秒";
                    messageTypes[1] = "10秒";
                    messageTypes[2] = "15秒";
                    builder = new AlertDialog.Builder(this);
                    builder.setTitle("选择切片时间")
                            .setSingleChoiceItems(messageTypes, meesageTypeIndex, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    meesageTypeIndex = which;
                                }
                            });
                    builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            switch (meesageTypeIndex) {
                                case 0:
                                    FFmpegFrameRecorderSlice.SEGMENT_DURATION = 5;
                                    localSliceStart(path,5,imei+"-"+media_name);
                                    Log.e("TAG", "5秒");
                                    break;
                                case 1:
                                    FFmpegFrameRecorderSlice.SEGMENT_DURATION = 10;
                                    localSliceStart(path,10,imei+"-"+media_name);
                                    Log.e("TAG", "10秒");
                                    break;
                                case 2:
                                    FFmpegFrameRecorderSlice.SEGMENT_DURATION = 15;
                                    localSliceStart(path,15,imei+"-"+media_name);
                                    Log.e("TAG", "15秒");
                                    break;

                            }
                            dialog.dismiss();
                        }
                    });
                    builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    builder.show();
                }
                break;

            default:
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * 获取视频全路径 Created by 波仔糕 on 2016.06.13.
     */

    public String getRealFilePath(final Context context, final Uri uri) {
        if (null == uri)
            return "";
        final String scheme = uri.getScheme();
        String data = null;
        if (scheme == null)
            data = uri.getPath();
        else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            data = uri.getPath();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
            if (null != cursor) {
                if (cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                    if (index > -1) {
                        data = cursor.getString(index);
                    }
                }
                cursor.close();
            }
        }
        return data;
    }

    /**
     * 开始对本地媒体文件进行切片 add by 波仔糕 on 2016/9/16
     */
    public void localSliceStart(final String path,final int slice_time, final String media_name) {
        //切片存放路径
        String local_slice_path = Environment.getExternalStorageDirectory().getPath() + "/local_media_slice/";
        File file = new File(local_slice_path);
        if (!file.exists()) {
            try {
                //如果不存在就创建该文件目录
                file.mkdirs();
            } catch (java.lang.Exception e) {
            }
        }
        DeleteFiles(file);
       String command = "-y -i "+path+" -codec copy -vbsf h264_mp4toannexb -f hls -hls_time "+slice_time+" -hls_list_size 0 /sdcard/local_media_slice/"+media_name+".m3u8";
        execFFmpegBinary(command.split(" "));
    }


    /**
     * 递归删除文件
     *
     * @param file
     */
    public void DeleteFiles(File file) {
        if (file.isDirectory()) {
            File[] childFile = file.listFiles();
            for (File f : childFile) {
                DeleteFiles(f);
            }
        }
        if (!file.isDirectory()) {
            file.delete();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void startPreview(SurfaceTexture surfaceTexture) {
        if (mCamera == null) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = CameraHelper.getOptimalSize(previewSizes,
                PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT);
        // if changed, reassign values and request layout
        if (previewWidth != previewSize.width || previewHeight != previewSize.height) {
            previewWidth = previewSize.width;
            previewHeight = previewSize.height;
            // Switch width and height
            mPreview.setPreviewSize(previewHeight, previewWidth);
            mPreview.requestLayout();
        }
        parameters.setPreviewSize(previewWidth, previewHeight);
//        parameters.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(parameters);

        mCamera.setDisplayOrientation(CameraHelper.getCameraDisplayOrientation(
                this, mCameraId));
        mCamera.autoFocus(null);
        // YCbCr_420_SP (NV21) format
        byte[] bufferByte = new byte[previewWidth * previewHeight * 3 / 2];
        mCamera.addCallbackBuffer(bufferByte);
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                // get video data
                if (mRecording) {
                    // wait for AudioRecord to init and start
                    if (mAudioRecord == null || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        mRecordFragments.peek().setStartTimestamp(System.currentTimeMillis());
                        return;
                    }

                    // pop the current record fragment when calculate total recorded time
                    RecordFragment curFragment = mRecordFragments.pop();
                    long recordedTime = calculateTotalRecordedTime(mRecordFragments);
                    // push it back after calculation
                    mRecordFragments.push(curFragment);
                    long curRecordedTime = System.currentTimeMillis()
                            - curFragment.getStartTimestamp() + recordedTime;
                    // check if exceeds time limit
                    if (curRecordedTime > MAX_VIDEO_LENGTH) {
                        new FinishRecordingTask().execute();
                        return;
                    }

                    long timestamp = 1000 * curRecordedTime;
                    Frame frame;
                    RecordedFrame recordedFrame = mRecycledFrameQueue.poll();
                    if (recordedFrame != null) {
                        frame = recordedFrame.getFrame();
                        recordedFrame.setTimestamp(timestamp);
                    } else {
                        frame = new Frame(previewWidth, previewHeight, frameDepth, frameChannels);
                        recordedFrame = new RecordedFrame(timestamp, frame);
                    }
                    ((ByteBuffer) frame.image[0].position(0)).put(data);

                    try {
                        mRecordedFrameQueue.put(recordedFrame);
                        mRecordedFrameCount++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mCamera.addCallbackBuffer(data);
            }
        });

        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        mCamera.startPreview();
    }

    private void stopPreview() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
        }
    }

    private void acquireCamera() {
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();// release the camera for other applications
            mCamera = null;
        }
    }

    private void initRecorder() {
        mRecordedFrameQueue = new LinkedBlockingQueue<>();
        mRecycledFrameQueue = new ConcurrentLinkedQueue<>();
        mRecordFragments = new Stack<>();

        Log.i(LOG_TAG, "init mFrameRecorder");

        String recordedTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        mVideo = CameraHelper.getOutputMediaFile(recordedTime, CameraHelper.MEDIA_TYPE_VIDEO);
        Log.i(LOG_TAG, "Output Video: " + mVideo);

        mFrameRecorder = new FFmpegFrameRecorderSlice(mVideo, videoWidth, videoHeight, 1);
        mFrameRecorder.setFormat("mp4");
        mFrameRecorder.setSampleRate(sampleAudioRateInHz);
        mFrameRecorder.setFrameRate(frameRate);
        // Use H264
        mFrameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        mFrameRecorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);

        Log.i(LOG_TAG, "mFrameRecorder initialize success");
    }

    private void releaseRecorder() {
        mRecordedFrameQueue = null;
        mRecycledFrameQueue = null;
        mRecordFragments = null;

        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.release();
            } catch (FFmpegFrameRecorderSlice.Exception e) {
                e.printStackTrace();
            }
        }
        mFrameRecorder = null;
    }

    private void startRecorder() {
        try {
            mFrameRecorder.start();
        } catch (FFmpegFrameRecorderSlice.Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecorder(boolean saveFile) {
        if (mFrameRecorder != null) {
            try {
                mFrameRecorder.stop();
            } catch (FFmpegFrameRecorderSlice.Exception e) {
                e.printStackTrace();
            }
            if (!saveFile) {
                mVideo.delete();
            }
        }
    }

    private void startRecording() {
        mVideoRecordThread = new VideoRecordThread();
        mVideoRecordThread.start();

        mAudioRecordThread = new AudioRecordThread();
        mAudioRecordThread.start();
    }

    private void stopRecording() {
        if (mAudioRecordThread != null) {
            mAudioRecordThread.stopRunning();
            try {
                mAudioRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mAudioRecordThread = null;
        }

        if (mVideoRecordThread != null) {
            mVideoRecordThread.stopRunning();
            try {
                mVideoRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mVideoRecordThread = null;
        }
    }

    private void resumeRecording() {
        if (!mRecording) {
            RecordFragment recordFragment = new RecordFragment();
            recordFragment.setStartTimestamp(System.currentTimeMillis());
            mRecordFragments.push(recordFragment);
           /* runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnSwitchCamera.setVisibility(View.INVISIBLE);
                }
            });*/
            mRecording = true;
        }
    }

    private void pauseRecording() {
        if (mRecording) {
            mRecordFragments.peek().setEndTimestamp(System.currentTimeMillis());
           /* runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mBtnSwitchCamera.setVisibility(View.VISIBLE);
                }
            });*/
            mRecording = false;
        }
    }

    private long calculateTotalRecordedTime(Stack<RecordFragment> recordFragments) {
        long recordedTime = 0;
        for (RecordFragment recordFragment : recordFragments) {
            recordedTime += recordFragment.getDuration();
        }
        return recordedTime;
    }

    class AudioRecordThread extends Thread {

        private boolean isRunning;

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            ShortBuffer audioData = ShortBuffer.allocate(bufferSize);

            Log.d(LOG_TAG, "mAudioRecord.startRecording()");
            mAudioRecord.startRecording();

            isRunning = true;
            /* ffmpeg_audio encoding loop */
            while (isRunning) {
                if (mRecording && mFrameRecorder != null) {
                    int bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                    audioData.limit(bufferReadResult);
                    if (bufferReadResult > 0) {
                        Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                        try {
                            mFrameRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorderSlice.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(LOG_TAG, "AudioThread Finished, release mAudioRecord");

            /* encoding finish, release mFrameRecorder */
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
                Log.v(LOG_TAG, "mAudioRecord released");
            }
        }

        public void stopRunning() {
            this.isRunning = false;
        }
    }

    class VideoRecordThread extends Thread {

        private boolean isRunning;

        @Override
        public void run() {
            List<String> filters = new ArrayList<>();
            // Transpose
            String transpose = null;
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(mCameraId, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                switch (info.orientation) {
                    case 270:
//                        transpose = "transpose=clock_flip"; // Same as preview display
                        transpose = "transpose=cclock"; // Mirrored horizontally as preview display
                        break;
                    case 90:
//                        transpose = "transpose=cclock_flip"; // Same as preview display
                        transpose = "transpose=clock"; // Mirrored horizontally as preview display
                        break;
                }
            } else {
                switch (info.orientation) {
                    case 270:
                        transpose = "transpose=cclock";
                        break;
                    case 90:
                        transpose = "transpose=clock";
                        break;
                }
            }
            if (transpose != null) {
                filters.add(transpose);
            }
            // Crop (only vertically)
            int width = previewHeight;
            int height = width * videoHeight / videoWidth;
            String crop = String.format("crop=%d:%d:%d:%d",
                    width, height,
                    (previewHeight - width) / 2, (previewWidth - height) / 2);
            filters.add(crop);
            // Scale (to designated size)
            String scale = String.format("scale=%d:%d", videoHeight, videoWidth);
            filters.add(scale);

            FFmpegFrameFilter frameFilter = new FFmpegFrameFilter(TextUtils.join(",", filters),
                    previewWidth, previewHeight);
            frameFilter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
            frameFilter.setFrameRate(frameRate);
            try {
                frameFilter.start();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

            isRunning = true;
            RecordedFrame recordedFrame;

            int frameIndex = 0;
            int step = 1;
            final int SAMPLE_LENGTH = 30;
            long[] processFrameTimeSample = new long[SAMPLE_LENGTH];
            int sampleIndex = 0;

            while (isRunning) {
                try {
                    recordedFrame = mRecordedFrameQueue.take();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    try {
                        frameFilter.stop();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                mProcessedFrameCount++;
                /* Process only 1st frame in every [step] frames,
                in case the recorded frame queue gets bigger and bigger,
                eventually run out of memory. */
                frameIndex = (frameIndex + 1) % step;
                if (frameIndex == 0) {
                    if (mFrameRecorder != null) {
                        long timestamp = recordedFrame.getTimestamp();
                        if (timestamp > mFrameRecorder.getTimestamp()) {
                            mFrameRecorder.setTimestamp(timestamp);
                        }
                        long startTime = System.currentTimeMillis();
                        Frame filteredFrame = null;
                        try {
                            frameFilter.push(recordedFrame.getFrame());
                            filteredFrame = frameFilter.pull();
                        } catch (FrameFilter.Exception e) {
                            e.printStackTrace();
                        }
                        try {
                            mFrameRecorder.record(filteredFrame, avutil.AV_PIX_FMT_NV21);
                        } catch (FFmpegFrameRecorderSlice.Exception e) {
                            e.printStackTrace();
                        }
                        long endTime = System.currentTimeMillis();
                        long processTime = endTime - startTime;
                        processFrameTimeSample[sampleIndex] = processTime;
                        mTotalProcessFrameTime += processTime;
                        Log.d(LOG_TAG, "this process time: " + processTime);
                        long totalAvg = mTotalProcessFrameTime / mProcessedFrameCount;
                        Log.d(LOG_TAG, "avg process time: " + totalAvg);
                        // TODO looking for a better way to adjust the process time per frame, hopefully to keep up with the onPreviewFrame callback frequency
                        if (sampleIndex == SAMPLE_LENGTH - 1) {
                            long sampleSum = 0;
                            for (long pft : processFrameTimeSample) {
                                sampleSum += pft;
                            }
                            long sampleAvg = sampleSum / SAMPLE_LENGTH;
                            double tolerance = 0.25;
                            if (sampleAvg > totalAvg * (1 + tolerance)) {
                                // ignore more frames
                                step++;
                                Log.i(LOG_TAG, "increase step to " + step);
                            } else if (sampleAvg < totalAvg * (1 - tolerance)) {
                                // ignore less frames
                                if (step > 1) {
                                    step--;
                                    Log.i(LOG_TAG, "decrease step to " + step);
                                }
                            }
                        }
                        sampleIndex = (sampleIndex + 1) % SAMPLE_LENGTH;
                    }
                }
                Log.d(LOG_TAG, mProcessedFrameCount + " / " + mRecordedFrameCount);
                mRecycledFrameQueue.offer(recordedFrame);
            }
        }

        public void stopRunning() {
            while (getState() != WAITING) {
            }
            this.isRunning = false;
            interrupt();
        }
    }

    class FinishRecordingTask extends AsyncTask<Void, Integer, Void> {

        ProgressDialog mProgressDialog;

        @Override
        protected Void doInBackground(Void... params) {
            pauseRecording();
            stopRecording();
            stopRecorder(true);
            releaseRecorder();
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mProgressDialog = ProgressDialog.show(FFmpegRecordActivity.this,
                    null, "正在结束", true);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            Toast.makeText(FFmpegRecordActivity.this, "实时切片存放位置:root/real_time_slice", Toast.LENGTH_SHORT).show();
            mProgressDialog.dismiss();
//
//            Intent intent = new Intent(FFmpegRecordActivity.this, PlaybackActivity.class);
//            intent.putExtra(PlaybackActivity.INTENT_NAME_VIDEO_PATH, mVideo.getPath());
//            startActivity(intent);
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            mProgressDialog.setProgress(values[0]);
        }
    }
}
