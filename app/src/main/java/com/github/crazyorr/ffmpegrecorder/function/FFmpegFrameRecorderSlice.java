package com.github.crazyorr.ffmpegrecorder.function;
/**
 *  实时流切片 add by 波仔糕 on 2016/9/25
 */
import android.os.Environment;
import android.util.Log;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.ShortPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avcodec.AVPicture;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVOutputFormat;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVFrame;
import org.bytedeco.javacpp.avutil.AVRational;
import org.bytedeco.javacpp.swresample;
import org.bytedeco.javacpp.swresample.SwrContext;
import org.bytedeco.javacpp.swscale;
import org.bytedeco.javacpp.swscale.SwsContext;
import org.bytedeco.javacpp.swscale.SwsFilter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Iterator;
import java.util.Map.Entry;

public class FFmpegFrameRecorderSlice extends FrameRecorder {
    private static Exception loadingException = null;
    private String filename;
    private AVFrame picture;
    private AVFrame tmp_picture;
    private BytePointer picture_buf;
    private BytePointer video_outbuf;
    private int video_outbuf_size;
    private AVFrame frame;
    private Pointer[] samples_in;
    private BytePointer[] samples_out;
    private PointerPointer samples_in_ptr;
    private PointerPointer samples_out_ptr;
    private BytePointer audio_outbuf;
    private int audio_outbuf_size;
    private int audio_input_frame_size;
    private AVOutputFormat oformat;
    private AVFormatContext oc;
    private AVCodec video_codec;
    private AVCodec audio_codec;
    private AVCodecContext video_c;
    private AVCodecContext audio_c;
    private AVStream video_st;
    private AVStream audio_st;
    private SwsContext img_convert_ctx;
    private SwrContext samples_convert_ctx;
    private int samples_channels;
    private int samples_format;
    private int samples_rate;
    private AVPacket video_pkt;
    private AVPacket audio_pkt;
    private int[] got_video_packet;
    private int[] got_audio_packet;
    //TS实时切片变量声明
    private int IsAACCodes = 0;
    private int video_stream_idx = -1;
    private int audio_stream_idx = -1;
    private avformat.AVStream oaudio_st = new avformat.AVStream();
    private avformat.AVStream ovideo_st = new avformat.AVStream(null);
    private avcodec.AVBitStreamFilterContext vbsf_h264_toannexb = null;
    private avcodec.AVBitStreamFilterContext vbsf_aac_adtstoasc = null;
    //切片时间大小
    public static int SEGMENT_DURATION = 10;
    //在磁盘上一共最多存储多少个分片
    private final long NUM_SEGMENTS = Long.MAX_VALUE;

    private enum AVMediaType {
        AVMEDIA_TYPE_VIDEO,
        AVMEDIA_TYPE_AUDIO
    }

    //声称切片的文件名字
    private BytePointer m_output_file_name = null;
    //生成目录
    private String URL_PREFIX;
    //生成的m3u8文件名
    public static String M3U8_FILE_NAME;
    //切割文件的前缀
    public  static String OUTPUT_PREFIX;
    //m3u8 param
    private int m_output_index = 1;//生成的切片文件顺序编号
    private String TAG = "TAG";
    //packet 中的ID ，如果先加入音频 pocket 则音频是 0  视频是1，否则相反(影响add_out_stream顺序)
    private final int AUDIO_ID = 0;
    private final int VIDEO_ID = 1;
    private avformat.AVFormatContext ocodec = new avformat.AVFormatContext();
    boolean write_flag = true;
    private int first_segment = 1;     //第一个分片的标号
    private int last_segment = 0;      //最后一个分片标号
    private int decode_done = 0;       //文件是否读取完成
    private int remove_file = 0;       //是否要移除文件（写在磁盘的分片已经达到最大）
    private String remove_filename = "";    //要从磁盘上删除的文件名称
    private double prev_segment_time_video = 0;//视频流上一个分片时间
    private double prev_segment_time_audio = 0;//音频流上一个分片时间
    private int ret1 = 0, ret2 = 0;
    private int[] actual_segment_durations = new int[1024]; //各个分片文件实际的长度

    public static FFmpegFrameRecorderSlice createDefault(File f, int w, int h) throws Exception {
        return new FFmpegFrameRecorderSlice(f, w, h);
    }

    public static FFmpegFrameRecorderSlice createDefault(String f, int w, int h) throws Exception {
        return new FFmpegFrameRecorderSlice(f, w, h);
    }

    public static void tryLoad() throws Exception {
        if (loadingException != null) {
            throw loadingException;
        } else {
            try {
                Loader.load(avutil.class);
                Loader.load(swresample.class);
                Loader.load(avcodec.class);
                Loader.load(avformat.class);
                Loader.load(swscale.class);
                avformat.av_register_all();
                avformat.avformat_network_init();
            } catch (Throwable var1) {
                if (var1 instanceof Exception) {
                    throw loadingException = (Exception) var1;
                } else {
                    throw loadingException = new Exception("Failed to load " + FFmpegFrameRecorderSlice.class, var1);
                }
            }
        }
    }

    public FFmpegFrameRecorderSlice(File file, int audioChannels) {
        this((File) file, 0, 0, audioChannels);
    }

    public FFmpegFrameRecorderSlice(String filename, int audioChannels) {
        this((String) filename, 0, 0, audioChannels);
    }

    public FFmpegFrameRecorderSlice(File file, int imageWidth, int imageHeight) {
        this((File) file, imageWidth, imageHeight, 0);
    }

    public FFmpegFrameRecorderSlice(String filename, int imageWidth, int imageHeight) {
        this((String) filename, imageWidth, imageHeight, 0);
    }

    public FFmpegFrameRecorderSlice(File file, int imageWidth, int imageHeight, int audioChannels) {
        this(file.getAbsolutePath(), imageWidth, imageHeight, audioChannels);
    }

    public FFmpegFrameRecorderSlice(String filename, int imageWidth, int imageHeight, int audioChannels) {
        this.filename = filename;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.audioChannels = audioChannels;
        this.pixelFormat = -1;
        this.videoCodec = 0;
        this.videoBitrate = 400000;
        this.frameRate = 30.0D;
        this.sampleFormat = -1;
        this.audioCodec = 0;
        this.audioBitrate = '切';
        this.sampleRate = '걄';
        this.interleaved = true;
        this.video_pkt = new AVPacket();
        this.audio_pkt = new AVPacket();
        //初始化ffmpeg环境参数
        avformat.av_register_all();
        avformat.avformat_network_init();
        avcodec.avcodec_register_all();
        //切片存放路径
        URL_PREFIX = Environment.getExternalStorageDirectory().getPath() + "/real_time_slice/";
        File path = new File(URL_PREFIX);
        if (!path.exists()) {
            try {
                //如果不存在就创建该文件目录
                path.mkdirs();
            } catch (java.lang.Exception e) {
            }
        }
        DeleteFiles(path);
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

    public void release() throws Exception {
        Class var1 = avcodec.class;
        synchronized (avcodec.class) {
            this.releaseUnsafe();
        }
    }

    void releaseUnsafe() throws Exception {
        if (this.video_c != null) {
            avcodec.avcodec_close(this.video_c);
            this.video_c = null;
        }

        if (this.audio_c != null) {
            avcodec.avcodec_close(this.audio_c);
            this.audio_c = null;
        }

        if (this.picture_buf != null) {
            avutil.av_free(this.picture_buf);
            this.picture_buf = null;
        }

        if (this.picture != null) {
            avutil.av_frame_free(this.picture);
            this.picture = null;
        }

        if (this.tmp_picture != null) {
            avutil.av_frame_free(this.tmp_picture);
            this.tmp_picture = null;
        }

        if (this.video_outbuf != null) {
            avutil.av_free(this.video_outbuf);
            this.video_outbuf = null;
        }

        if (this.frame != null) {
            avutil.av_frame_free(this.frame);
            this.frame = null;
        }

        int nb_streams;
        if (this.samples_out != null) {
            for (nb_streams = 0; nb_streams < this.samples_out.length; ++nb_streams) {
                avutil.av_free(this.samples_out[nb_streams].position(0));
            }

            this.samples_out = null;
        }

        if (this.audio_outbuf != null) {
            avutil.av_free(this.audio_outbuf);
            this.audio_outbuf = null;
        }

        if (this.video_st != null && this.video_st.metadata() != null) {
            avutil.av_dict_free(this.video_st.metadata());
            this.video_st.metadata((AVDictionary) null);
        }

        if (this.audio_st != null && this.audio_st.metadata() != null) {
            avutil.av_dict_free(this.audio_st.metadata());
            this.audio_st.metadata((AVDictionary) null);
        }

        this.video_st = null;
        this.audio_st = null;
        if (this.oc != null && !this.oc.isNull()) {
            if ((this.oformat.flags() & 1) == 0) {
                avformat.avio_close(this.oc.pb());
            }

            nb_streams = this.oc.nb_streams();

            for (int i = 0; i < nb_streams; ++i) {
                avutil.av_free(this.oc.streams(i).codec());
                avutil.av_free(this.oc.streams(i));
            }

            if (this.oc.metadata() != null) {
                avutil.av_dict_free(this.oc.metadata());
                this.oc.metadata((AVDictionary) null);
            }

            avutil.av_free(this.oc);
            this.oc = null;
        }

        if (this.img_convert_ctx != null) {
            swscale.sws_freeContext(this.img_convert_ctx);
            this.img_convert_ctx = null;
        }

        if (this.samples_convert_ctx != null) {
            swresample.swr_free(this.samples_convert_ctx);
            this.samples_convert_ctx = null;
        }

    }

    protected void finalize() throws Throwable {
        super.finalize();
        this.release();
    }

    public int getFrameNumber() {
        return this.picture == null ? super.getFrameNumber() : (int) this.picture.pts();
    }

    public void setFrameNumber(int frameNumber) {
        if (this.picture == null) {
            super.setFrameNumber(frameNumber);
        } else {
            this.picture.pts((long) frameNumber);
        }

    }

    public long getTimestamp() {
        return Math.round((double) ((long) this.getFrameNumber() * 1000000L) / this.getFrameRate());
    }

    public void setTimestamp(long timestamp) {
        this.setFrameNumber((int) Math.round((double) timestamp * this.getFrameRate() / 1000000.0D));
    }

    public void start() throws Exception {
        Class var1 = avcodec.class;
        synchronized (avcodec.class) {
            this.startUnsafe();
        }
    }

    /**
     * 初始化混流 add by 波仔糕 on 2016/9/1
     */
    private void init_mux() {
        int ret = 0;
    /* allocate the output media context */
        avformat.avformat_alloc_output_context2(ocodec, null, null, m_output_file_name.getString());
        if (ocodec == null) {
            return;
        }
        avformat.AVOutputFormat ofmt = null;
        ofmt = ocodec.oformat();

	  /*open the output file, if needed */
        try {
            if ((ofmt.flags() & avformat.AVFMT_NOFILE) == 0) {
                avformat.AVIOContext pb = new avformat.AVIOContext(null);
                if (avformat.avio_open(pb, m_output_file_name, avformat.AVIO_FLAG_WRITE) < 0) {
                    Log.e(TAG, "Could not open" + m_output_file_name);
                    return;
                }
                ocodec.pb(pb);
            }
        } catch (java.lang.Exception e) {
            e.printStackTrace();
        }

        //这里添加的时候AUDIO_ID/VIDEO_ID有影响
        //添加音频信息到输出context
        if (audio_stream_idx != -1)//如果存在音频
        {
            oaudio_st = add_out_stream(ocodec, AVMediaType.AVMEDIA_TYPE_AUDIO);
        }

        //添加视频信息到输出context
        if (video_stream_idx != -1)//如果存在视频
        {
            ovideo_st = add_out_stream(ocodec, AVMediaType.AVMEDIA_TYPE_VIDEO);
        }

        avformat.av_dump_format(ocodec, 0, m_output_file_name, 1);

        ret = avformat.avformat_write_header(ocodec, (PointerPointer) null);
        if (ret != 0) {
            Log.e(TAG, "Call avformat_write_header function failed.\n");
            return;
        }
        return;
    }

    /**
     * 续流 add by 波仔糕 on 2016/9/1
     */
    private avformat.AVStream add_out_stream(avformat.AVFormatContext output_format_context, AVMediaType codec_type_t) {
        avformat.AVStream in_stream = null;
        avformat.AVStream output_stream = null;
        avcodec.AVCodecContext output_codec_context = null;

        output_stream = avformat.avformat_new_stream(output_format_context, null);
        if (output_stream == null) {
            return null;
        }

        int u = audio_stream_idx;
        int y = video_stream_idx;
        switch (codec_type_t) {
            case AVMEDIA_TYPE_AUDIO:
                in_stream = this.oc.streams(audio_stream_idx);
                break;
            case AVMEDIA_TYPE_VIDEO:
                in_stream = this.oc.streams(video_stream_idx);
                break;
            default:
                break;
        }

        output_stream.id(output_format_context.nb_streams() - 1);
        output_codec_context = output_stream.codec();
        output_stream.time_base(in_stream.time_base());

        int ret = 0;
        ret = avcodec.avcodec_copy_context(output_stream.codec(), in_stream.codec());
        if (ret < 0) {
            Log.e(TAG, "Failed to copy context from input to output stream codec context\n");
            return null;
        }

        //这个很重要，要么纯复用解复用，不做编解码写头会失败,
        //另或者需要编解码如果不这样，生成的文件没有预览图，还有添加下面的header失败，置0之后会重新生成extradata
        output_codec_context.codec_tag(0);

        //if(! strcmp( output_format_context-> oformat-> name,  "mp4" ) ||
        //!strcmp (output_format_context ->oformat ->name , "mov" ) ||
        //!strcmp (output_format_context ->oformat ->name , "3gp" ) ||
        //!strcmp (output_format_context ->oformat ->name , "flv"))
        if ((avformat.AVFMT_GLOBALHEADER & output_format_context.oformat().flags()) != 0) {
            output_codec_context.flags(output_codec_context.flags() | avcodec.CODEC_FLAG_GLOBAL_HEADER);
        }
        return output_stream;
    }

    void startUnsafe() throws Exception {
        this.picture = null;
        this.tmp_picture = null;
        this.picture_buf = null;
        this.frame = null;
        this.video_outbuf = null;
        this.audio_outbuf = null;
        this.oc = null;
        this.video_c = null;
        this.audio_c = null;
        this.video_st = null;
        this.audio_st = null;
        this.got_video_packet = new int[1];
        this.got_audio_packet = new int[1];
        String format_name = this.format != null && this.format.length() != 0 ? this.format : null;

        if ((this.oformat = avformat.av_guess_format(format_name, this.filename, (String) null)) == null) {
            int options = this.filename.indexOf("://");
            if (options > 0) {
                format_name = this.filename.substring(0, options);
            }

            if ((this.oformat = avformat.av_guess_format(format_name, this.filename, (String) null)) == null) {
                throw new Exception("av_guess_format() error: Could not guess output format for \"" + this.filename + "\" and " + this.format + " format.");
            }
        }

        format_name = this.oformat.name().getString();
        if ((this.oc = avformat.avformat_alloc_context()) == null) {
            throw new Exception("avformat_alloc_context() error: Could not allocate format context");
        } else {
            this.oc.oformat(this.oformat);
            this.oc.filename().putString(this.filename);
            int e;
            if (this.imageWidth > 0 && this.imageHeight > 0) {
                if (this.videoCodec != 0) {
                    this.oformat.video_codec(this.videoCodec);
                } else if ("flv".equals(format_name)) {
                    this.oformat.video_codec(22);
                } else if ("mp4".equals(format_name)) {
                    this.oformat.video_codec(13);
                } else if ("3gp".equals(format_name)) {
                    this.oformat.video_codec(5);
                } else if ("avi".equals(format_name)) {
                    this.oformat.video_codec(26);
                }

                if ((this.video_codec = avcodec.avcodec_find_encoder_by_name(this.videoCodecName)) == null && (this.video_codec = avcodec.avcodec_find_encoder(this.oformat.video_codec())) == null) {
                    this.release();
                    throw new Exception("avcodec_find_encoder() error: Video codec not found.");
                }

                this.oformat.video_codec(this.video_codec.id());
                AVRational var9 = avutil.av_d2q(this.frameRate, 1001000);
                AVRational metadata = this.video_codec.supported_framerates();
                if (metadata != null) {
                    e = avutil.av_find_nearest_q_idx(var9, metadata);
                    var9 = metadata.position(e);
                }

                if ((this.video_st = avformat.avformat_new_stream(this.oc, this.video_codec)) == null) {
                    this.release();
                    throw new Exception("avformat_new_stream() error: Could not allocate video stream.");
                }

                this.video_c = this.video_st.codec();
                this.video_c.codec_id(this.oformat.video_codec());
                this.video_c.codec_type(0);
                this.video_c.bit_rate(this.videoBitrate);
                this.video_c.width((this.imageWidth + 15) / 16 * 16);
                this.video_c.height(this.imageHeight);
                if (this.aspectRatio > 0.0D) {
                    AVRational var15 = avutil.av_d2q(this.aspectRatio, 255);
                    this.video_c.sample_aspect_ratio(var15);
                    this.video_st.sample_aspect_ratio(var15);
                }

                this.video_c.time_base(avutil.av_inv_q(var9));
                this.video_st.time_base(avutil.av_inv_q(var9));
                if (this.gopSize >= 0) {
                    this.video_c.gop_size(this.gopSize);
                }

                if (this.videoQuality >= 0.0D) {
                    this.video_c.flags(this.video_c.flags() | 2);
                    this.video_c.global_quality((int) Math.round(118.0D * this.videoQuality));
                }

                if (this.pixelFormat != -1) {
                    this.video_c.pix_fmt(this.pixelFormat);
                } else if (this.video_c.codec_id() != 14 && this.video_c.codec_id() != 62 && this.video_c.codec_id() != 26 && this.video_c.codec_id() != 34) {
                    this.video_c.pix_fmt(0);
                } else {
                    this.video_c.pix_fmt(avutil.AV_PIX_FMT_RGB32);
                }

                if (this.video_c.codec_id() == 2) {
                    this.video_c.max_b_frames(2);
                } else if (this.video_c.codec_id() == 1) {
                    this.video_c.mb_decision(2);
                } else if (this.video_c.codec_id() == 5) {
                    if (this.imageWidth <= 128 && this.imageHeight <= 96) {
                        this.video_c.width(128).height(96);
                    } else if (this.imageWidth <= 176 && this.imageHeight <= 144) {
                        this.video_c.width(176).height(144);
                    } else if (this.imageWidth <= 352 && this.imageHeight <= 288) {
                        this.video_c.width(352).height(288);
                    } else if (this.imageWidth <= 704 && this.imageHeight <= 576) {
                        this.video_c.width(704).height(576);
                    } else {
                        this.video_c.width(1408).height(1152);
                    }
                } else if (this.video_c.codec_id() == 28) {
                    this.video_c.profile(578);
                }

                if ((this.oformat.flags() & 64) != 0) {
                    this.video_c.flags(this.video_c.flags() | 4194304);
                }

                if ((this.video_codec.capabilities() & 512) != 0) {
                    this.video_c.strict_std_compliance(-2);
                }
            }

            int var11;
            if (this.audioChannels > 0 && this.audioBitrate > 0 && this.sampleRate > 0) {
                if (this.audioCodec != 0) {
                    this.oformat.audio_codec(this.audioCodec);
                } else if (!"flv".equals(format_name) && !"mp4".equals(format_name) && !"3gp".equals(format_name)) {
                    if ("avi".equals(format_name)) {
                        this.oformat.audio_codec(65536);
                    }
                } else {
                    this.oformat.audio_codec(86018);
                }

                if ((this.audio_codec = avcodec.avcodec_find_encoder_by_name(this.audioCodecName)) == null && (this.audio_codec = avcodec.avcodec_find_encoder(this.oformat.audio_codec())) == null) {
                    this.release();
                    throw new Exception("avcodec_find_encoder() error: Audio codec not found.");
                }

                this.oformat.audio_codec(this.audio_codec.id());
                if ((this.audio_st = avformat.avformat_new_stream(this.oc, this.audio_codec)) == null) {
                    this.release();
                    throw new Exception("avformat_new_stream() error: Could not allocate audio stream.");
                }

                this.audio_c = this.audio_st.codec();
                this.audio_c.codec_id(this.oformat.audio_codec());
                this.audio_c.codec_type(1);
                this.audio_c.bit_rate(this.audioBitrate);
                this.audio_c.sample_rate(this.sampleRate);
                this.audio_c.channels(this.audioChannels);
                this.audio_c.channel_layout(avutil.av_get_default_channel_layout(this.audioChannels));
                if (this.sampleFormat != -1) {
                    this.audio_c.sample_fmt(this.sampleFormat);
                } else {
                    this.audio_c.sample_fmt(8);
                    IntPointer var10 = this.audio_c.codec().sample_fmts();

                    for (var11 = 0; var10.get(var11) != -1; ++var11) {
                        if (var10.get(var11) == 1) {
                            this.audio_c.sample_fmt(1);
                            break;
                        }
                    }
                }

                this.audio_c.time_base().num(1).den(this.sampleRate);
                this.audio_st.time_base().num(1).den(this.sampleRate);
                switch (this.audio_c.sample_fmt()) {
                    case 0:
                    case 5:
                        this.audio_c.bits_per_raw_sample(8);
                        break;
                    case 1:
                    case 6:
                        this.audio_c.bits_per_raw_sample(16);
                        break;
                    case 2:
                    case 7:
                        this.audio_c.bits_per_raw_sample(32);
                        break;
                    case 3:
                    case 8:
                        this.audio_c.bits_per_raw_sample(32);
                        break;
                    case 4:
                    case 9:
                        this.audio_c.bits_per_raw_sample(64);
                        break;
                    default:
                        assert false;
                }

                if (this.audioQuality >= 0.0D) {
                    this.audio_c.flags(this.audio_c.flags() | 2);
                    this.audio_c.global_quality((int) Math.round(118.0D * this.audioQuality));
                }

                if ((this.oformat.flags() & 64) != 0) {
                    this.audio_c.flags(this.audio_c.flags() | 4194304);
                }

                if ((this.audio_codec.capabilities() & 512) != 0) {
                    this.audio_c.strict_std_compliance(-2);
                }
            }
            avformat.av_dump_format(this.oc, 0, this.filename, 1);
            int ret;
            AVDictionary var12;
            Iterator var13;
            Entry var16;
            if (this.video_st != null) {
                var12 = new AVDictionary((Pointer) null);
                if (this.videoQuality >= 0.0D) {
                    avutil.av_dict_set(var12, "crf", "" + this.videoQuality, 0);
                }
                var13 = this.videoOptions.entrySet().iterator();

                while (var13.hasNext()) {
                    var16 = (Entry) var13.next();
                    avutil.av_dict_set(var12, (String) var16.getKey(), (String) var16.getValue(), 0);
                }

                if ((ret = avcodec.avcodec_open2(this.video_c, this.video_codec, var12)) < 0) {
                    this.release();
                    throw new Exception("avcodec_open2() error " + ret + ": Could not open video codec.");
                }

                avutil.av_dict_free(var12);
                this.video_outbuf = null;
                if ((this.oformat.flags() & 32) == 0) {
                    this.video_outbuf_size = Math.max(262144, 8 * this.video_c.width() * this.video_c.height());
                    this.video_outbuf = new BytePointer(avutil.av_malloc((long) this.video_outbuf_size));
                }

                if ((this.picture = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate picture.");
                }

                this.picture.pts(0L);
                var11 = avcodec.avpicture_get_size(this.video_c.pix_fmt(), this.video_c.width(), this.video_c.height());
                if ((this.picture_buf = new BytePointer(avutil.av_malloc((long) var11))).isNull()) {
                    this.release();
                    throw new Exception("av_malloc() error: Could not allocate picture buffer.");
                }

                if ((this.tmp_picture = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate temporary picture.");
                }

                AVDictionary var17 = new AVDictionary((Pointer) null);
                Iterator e1 = this.videoMetadata.entrySet().iterator();

                while (e1.hasNext()) {
                    Entry e2 = (Entry) e1.next();
                    avutil.av_dict_set(var17, (String) e2.getKey(), (String) e2.getValue(), 0);
                }

                this.video_st.metadata(var17);
            }

            if (this.audio_st != null) {
                var12 = new AVDictionary((Pointer) null);
                if (this.audioQuality >= 0.0D) {
                    avutil.av_dict_set(var12, "crf", "" + this.audioQuality, 0);
                }

                var13 = this.audioOptions.entrySet().iterator();

                while (var13.hasNext()) {
                    var16 = (Entry) var13.next();
                    avutil.av_dict_set(var12, (String) var16.getKey(), (String) var16.getValue(), 0);
                }

                if ((ret = avcodec.avcodec_open2(this.audio_c, this.audio_codec, var12)) < 0) {
                    this.release();
                    throw new Exception("avcodec_open2() error " + ret + ": Could not open audio codec.");
                }

                avutil.av_dict_free(var12);
                this.audio_outbuf_size = 262144;
                this.audio_outbuf = new BytePointer(avutil.av_malloc((long) this.audio_outbuf_size));
                if (this.audio_c.frame_size() <= 1) {
                    this.audio_outbuf_size = 16384;
                    this.audio_input_frame_size = this.audio_outbuf_size / this.audio_c.channels();
                    switch (this.audio_c.codec_id()) {
                        case 65536:
                        case 65537:
                        case 65538:
                        case 65539:
                            this.audio_input_frame_size >>= 1;
                    }
                } else {
                    this.audio_input_frame_size = this.audio_c.frame_size();
                }

                var11 = avutil.av_sample_fmt_is_planar(this.audio_c.sample_fmt()) != 0 ? this.audio_c.channels() : 1;
                e = avutil.av_samples_get_buffer_size((IntPointer) null, this.audio_c.channels(), this.audio_input_frame_size, this.audio_c.sample_fmt(), 1) / var11;
                this.samples_out = new BytePointer[var11];

                for (int var18 = 0; var18 < this.samples_out.length; ++var18) {
                    this.samples_out[var18] = (new BytePointer(avutil.av_malloc((long) e))).capacity(e);
                }

                this.samples_in = new Pointer[8];
                this.samples_in_ptr = new PointerPointer(8);
                this.samples_out_ptr = new PointerPointer(8);
                if ((this.frame = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate audio frame.");
                }

                this.frame.pts(0L);
                AVDictionary var20 = new AVDictionary((Pointer) null);
                Iterator var23 = this.audioMetadata.entrySet().iterator();

                while (var23.hasNext()) {
                    Entry e3 = (Entry) var23.next();
                    avutil.av_dict_set(var20, (String) e3.getKey(), (String) e3.getValue(), 0);
                }

                this.audio_st.metadata(var20);
            }

            if ((this.oformat.flags() & 1) == 0) {
                AVIOContext var14 = new AVIOContext((Pointer) null);
                if ((ret = avformat.avio_open(var14, this.filename, 2)) < 0) {
                    this.release();
                    throw new Exception("avio_open error() error " + ret + ": Could not open \'" + this.filename + "\'");
                }

                this.oc.pb(var14);
            }

            var12 = new AVDictionary((Pointer) null);
            var13 = this.options.entrySet().iterator();

            while (var13.hasNext()) {
                var16 = (Entry) var13.next();
                avutil.av_dict_set(var12, (String) var16.getKey(), (String) var16.getValue(), 0);
            }

            AVDictionary var19 = new AVDictionary((Pointer) null);
            Iterator var21 = this.metadata.entrySet().iterator();

            while (var21.hasNext()) {
                Entry var22 = (Entry) var21.next();
                avutil.av_dict_set(var19, (String) var22.getKey(), (String) var22.getValue(), 0);
            }

            avformat.avformat_write_header(this.oc.metadata(var19), var12);
            avutil.av_dict_free(var12);
        }
        init_demux();
        m_output_file_name = new BytePointer(URL_PREFIX + OUTPUT_PREFIX + "-" + m_output_index + ".ts");
        m_output_index++;
        //****************************************创建输出文件（写头部）
        init_mux();
        write_flag = !write_index_file(first_segment, last_segment, false, actual_segment_durations, SEGMENT_DURATION);

    }


    /**
     * 初始化分流配置 Created by 波仔糕 on 2016/9/1 0001.
     */
    private int init_demux() {
        //添加音频信息到输出context
        for (int i = 0; i < this.oc.nb_streams(); i++) {
            if (this.oc.streams(i).codec().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
                video_stream_idx = i;
            } else if (this.oc.streams(i).codec().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                audio_stream_idx = i;
            }
        }
        if (this.oc.streams(video_stream_idx).codec().codec_id() == avcodec.AV_CODEC_ID_H264)  //AV_CODEC_ID_H264
        {
            //这里注意："h264_mp4toannexb",一定是这个字符串，无论是 flv，mp4，mov格式
            vbsf_h264_toannexb = avcodec.av_bitstream_filter_init("h264_mp4toannexb");
        }
        if (this.oc.streams(audio_stream_idx).codec().codec_id() == avcodec.AV_CODEC_ID_AAC) //AV_CODEC_ID_AAC
        {
            IsAACCodes = 1;
        }

        return 1;
    }

    /**
     * 视频切片函数 add by 波仔糕 on 2016/9/1
     */
    private void video_slice_up(int SEGMENT_DURATION) throws FrameRecorder.Exception {
        //初始化分流配置
        //填写第一个输出文件名称
        int current_segment_duration;
        //视频分片时间
        double segment_time_video;
        //实时流
        segment_time_video = video_pkt.pts() * avutil.av_q2d(this.oc.streams(video_stream_idx).time_base());
        //这里是为了纠错，有文件pts为不可用值
        if (video_pkt.pts() < video_pkt.dts()) {
            video_pkt.pts(video_pkt.dts());
        }

        //视频
        video_pkt.pts(avutil.av_rescale_q_rnd(video_pkt.pts(), this.oc.streams(video_stream_idx)
                .time_base(), ovideo_st.time_base(), avutil.AV_ROUND_NEAR_INF));
        video_pkt.dts(avutil.av_rescale_q_rnd(video_pkt.dts(), this.oc.streams(video_stream_idx)
                .time_base(), ovideo_st.time_base(), avutil.AV_ROUND_NEAR_INF));
        //数据大于2147483647可能会存在截断误差，不过已经足够
        video_pkt.duration((int) avutil.av_rescale_q(video_pkt.duration(), this.oc.streams(video_stream_idx)
                .time_base(), ovideo_st.time_base()));

        video_pkt.stream_index(VIDEO_ID); //这里add_out_stream顺序有影响
        Log.e(TAG, "video\n");

        current_segment_duration = (int) (segment_time_video - prev_segment_time_video + 0.5);
        actual_segment_durations[last_segment] = (current_segment_duration > 0 ? current_segment_duration : 1);

        //处理视频流
        if (segment_time_video - prev_segment_time_video >= SEGMENT_DURATION) {
            ret1 = avformat.av_write_trailer(ocodec);   // close ts file and free memory
            if (ret1 < 0) {
                Log.e(TAG, "Warning: Could not av_write_trailer of stream\n");
            }

            avformat.avio_flush(ocodec.pb());
            avformat.avio_close(ocodec.pb());

            if (NUM_SEGMENTS != 0 && (last_segment - first_segment) >= NUM_SEGMENTS - 1) {
                remove_file = 1;
                first_segment++;
            } else {
                remove_file = 0;
            }

            if (write_flag) {
                write_flag = !write_index_file(first_segment, ++last_segment, false, actual_segment_durations, SEGMENT_DURATION);
            }

            if (remove_file != 0) {
                File file = new File(remove_filename);
                if (file.isFile() && file.exists())
                    file.delete();
            }
            //每一个ts切片的名字
            m_output_file_name = new BytePointer(URL_PREFIX + OUTPUT_PREFIX + "-" + m_output_index + ".ts");
            m_output_index++;
            avformat.AVIOContext pb = new avformat.AVIOContext(null);
            if (avformat.avio_open(pb, m_output_file_name, avformat.AVIO_FLAG_WRITE) < 0) {
                Log.e(TAG, "Could not open" + m_output_file_name);
                return;
            }
            ocodec.pb(pb);

            // Write a new header at the start of each file
            if (avformat.avformat_write_header(ocodec, (PointerPointer) null) != 0) {
                Log.e(TAG, "Could not write mpegts header to first output file\n");
                System.exit(1);
            }

            prev_segment_time_video = segment_time_video;
        }

        //视频流
        ret1 = avformat.av_interleaved_write_frame(ocodec, video_pkt);
        if (ret1 < 0) {
            Log.e(TAG, "Warning: Could not write frame of stream\n");
        } else if (ret1 > 0) {
            Log.e(TAG, "End of stream requested\n");
            avcodec.av_free_packet(video_pkt);
            return;
        }

        avcodec.av_free_packet(video_pkt);
        return;
    }

    /**
     * 音频切片函数 add by 波仔糕 on 2016/9/1
     */
    private void audio_slice_up(int SEGMENT_DURATION) throws FrameRecorder.Exception {
        //初始化分流配置
        //填写第一个输出文件名称
        int current_segment_duration;
        //视频分片时间
        double segment_time_video;
        //音频分片时间
        double segment_time_audio;
        //video_stream_idx
        //实时流
        segment_time_audio = audio_pkt.pts() * avutil.av_q2d(this.oc.streams(audio_stream_idx).time_base());
        //这里是为了纠错，有文件pts为不可用值
        if (audio_pkt.pts() < audio_pkt.dts()) {
            audio_pkt.pts(audio_pkt.dts());
        }

        //音频
        audio_pkt.pts(avutil.av_rescale_q_rnd(audio_pkt.pts(), this.oc.streams(audio_stream_idx)
                .time_base(), oaudio_st.time_base(), avutil.AV_ROUND_NEAR_INF));
        audio_pkt.dts(avutil.av_rescale_q_rnd(audio_pkt.dts(), this.oc.streams(audio_stream_idx)
                .time_base(), oaudio_st.time_base(), avutil.AV_ROUND_NEAR_INF));
        //数据大于2147483647可能会存在截断误差，不过已经足够
        audio_pkt.duration((int) avutil.av_rescale_q(audio_pkt.duration(), this.oc.streams(audio_stream_idx)
                .time_base(), oaudio_st.time_base()));

        audio_pkt.stream_index(AUDIO_ID); //这里add_out_stream顺序有影响
        Log.e(TAG, "audio\n");

        current_segment_duration = (int) (segment_time_audio - prev_segment_time_audio + 0.5);
        actual_segment_durations[last_segment] = (current_segment_duration > 0 ? current_segment_duration : 1);

        //处理音频流
        if (segment_time_audio - prev_segment_time_audio >= SEGMENT_DURATION) {
            ret2 = avformat.av_write_trailer(ocodec);   // close ts file and free memory
            if (ret2 < 0) {
                Log.e(TAG, "Warning: Could not av_write_trailer of stream\n");
            }

            avformat.avio_flush(ocodec.pb());
            avformat.avio_close(ocodec.pb());

            if (NUM_SEGMENTS != 0 && (last_segment - first_segment) >= NUM_SEGMENTS - 1) {
                remove_file = 1;
                first_segment++;
            } else {
                remove_file = 0;
            }

            if (write_flag) {
                write_flag = !write_index_file(first_segment, ++last_segment, false, actual_segment_durations, SEGMENT_DURATION);
            }

            if (remove_file != 0) {
                File file = new File(remove_filename);
                if (file.isFile() && file.exists())
                    file.delete();
            }
            //每一个ts切片的名字
            m_output_file_name = new BytePointer(URL_PREFIX + OUTPUT_PREFIX + "-" + m_output_index + ".ts");
            m_output_index++;
            avformat.AVIOContext pb = new avformat.AVIOContext(null);
            if (avformat.avio_open(pb, m_output_file_name, avformat.AVIO_FLAG_WRITE) < 0) {
                Log.e(TAG, "Could not open" + m_output_file_name);
                return;
            }
            ocodec.pb(pb);

            // Write a new header at the start of each file
            if (avformat.avformat_write_header(ocodec, (PointerPointer) null) != 0) {
                Log.e(TAG, "Could not write mpegts header to first output file\n");
                System.exit(1);
            }

            prev_segment_time_audio = segment_time_audio;
        }

        //音频流
        ret2 = avformat.av_interleaved_write_frame(ocodec, audio_pkt);
        if (ret2 < 0) {
            Log.e(TAG, "Warning: Could not write frame of stream\n");
        } else if (ret2 > 0) {
            Log.e(TAG, "End of stream requested\n");
            avcodec.av_free_packet(audio_pkt);
            return;
        }

        avcodec.av_free_packet(audio_pkt);
        return;
    }


    /**
     * 释放混流 add by 波仔糕 on 2016/9/1
     */
    private int uinit_mux() {
        int nRet = 0;
        nRet = avformat.av_write_trailer(ocodec);
        if (nRet < 0) {
            Log.e(TAG, "Call av_write_trailer function failed\n");
        }
        if (vbsf_aac_adtstoasc != null) {
            avcodec.av_bitstream_filter_close(vbsf_aac_adtstoasc);
            vbsf_aac_adtstoasc = null;
        }

        if ((ocodec.oformat().flags() & avformat.AVFMT_NOFILE) == 0) {
        /* Close the output file. */
            avformat.avio_close(ocodec.pb());
        }
        avutil.av_free(ocodec);
        return 1;
    }

    /**
     * 更新m3u8切片目录列表文件 add by 波仔糕 on 2016/9/1
     */
    boolean write_index_file(int first_segment, int last_segment, boolean end_flag, int actual_segment_durations[], int SEGMENT_DURATION) {
        FileWriter fileWriter = null;
        BufferedWriter write_buf = null;
        String readline = "";
        int i = 0;
        String m3u8_file_pathname;
        //m3u8文件全路径
        m3u8_file_pathname = URL_PREFIX + M3U8_FILE_NAME;
        File dir = new File(m3u8_file_pathname);
        if (!dir.exists()) {
            try {
                //如果不存在就创建该文件
                dir.createNewFile();
            } catch (java.lang.Exception e) {
            }
        }
        try {
            fileWriter = new FileWriter(m3u8_file_pathname, false);
            write_buf = new BufferedWriter(fileWriter);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (NUM_SEGMENTS != 0) {
            //#EXT-X-MEDIA-SEQUENCE：<Number> 播放列表文件中每个媒体文件的URI都有一个唯一的序列号。URI的序列号等于它之前那个RUI的序列号加一(没有填0)
            readline = "#EXTM3U\n#EXT-X-TARGETDURATION:" + SEGMENT_DURATION + "\n#EXT-X-MEDIA-SEQUENCE:" + first_segment;
        } else {
            readline = "#EXTM3U\n#EXT-X-TARGETDURATION:" + SEGMENT_DURATION;
        }
        try {
            write_buf.write(readline);
            readline = "";
        } catch (IOException e) {
            Log.e(TAG, "Could not write to m3u8 index file, will not continue writing to index file\n");
            e.printStackTrace();
            return true;
        }

        for (i = first_segment; i <= last_segment; i++) {
            readline += "\n#EXTINF:" + actual_segment_durations[i - 1] + ",\n" + OUTPUT_PREFIX + "-" + i + ".ts";
            try {
                write_buf.write(readline);
                readline = "";
            } catch (IOException e) {
                Log.e(TAG, "Could not write to m3u8 index file, will not continue writing to index file\n");
                e.printStackTrace();
            }
        }

        if (end_flag) {
            readline += "\n#EXT-X-ENDLIST\n";
            try {
                write_buf.write(readline);
            } catch (IOException e) {
                Log.e(TAG, "Could not write last file and endlist tag to m3u8 index file\n");
                e.printStackTrace();
            }
        }

        try {
            write_buf.close();
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void stop() throws Exception {
        if (this.oc != null) {
            try {
                while (this.video_st != null && this.recordImage(0, 0, 0, 0, 0, -1, (Buffer[]) null)) {
                    ;
                }

                while (true) {
                    if (this.audio_st == null || !this.recordSamples(0, 0, (Buffer[]) null)) {
                        if (this.interleaved && this.video_st != null && this.audio_st != null) {
                            avformat.av_interleaved_write_frame(this.oc, (AVPacket) null);
                        } else {
                            avformat.av_write_frame(this.oc, (AVPacket) null);
                        }

                        avformat.av_write_trailer(this.oc);
                        break;
                    }
                }
            } finally {
                this.release();
            }
        }
        //****************************************完成输出文件（写尾部）
        uinit_mux();

        if (NUM_SEGMENTS != 0 && (last_segment - first_segment) >= NUM_SEGMENTS - 1) {
            remove_file = 1;
            first_segment++;
        } else {
            remove_file = 0;
        }

        if (write_flag) {
            write_index_file(first_segment, ++last_segment, true, actual_segment_durations, SEGMENT_DURATION);
        }

        if (remove_file != 0) {
            File file = new File(remove_filename);
            if (file.isFile() && file.exists())
                file.delete();
        }
    }

    public void record(Frame frame) throws Exception {
        this.record(frame, -1);
    }

    public void record(Frame frame, int pixelFormat) throws Exception {
        if (frame != null && (frame.image != null || frame.samples != null)) {
            if (frame.image != null) {
                frame.keyFrame = this.recordImage(frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels, frame.imageStride, pixelFormat, frame.image);
            }

            if (frame.samples != null) {
                frame.keyFrame = this.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
            }
        } else {
            this.recordImage(0, 0, 0, 0, 0, pixelFormat, (Buffer[]) null);
        }

    }

    public boolean recordImage(int width, int height, int depth, int channels, int stride, int pixelFormat, Buffer... image) throws Exception {
        if (this.video_st == null) {
            throw new Exception("No video output stream (Is imageWidth > 0 && imageHeight > 0 and has start() been called?)");
        } else {
            if (image != null && image.length != 0) {
                int step = stride * Math.abs(depth) / 8;
                BytePointer data = image[0] instanceof ByteBuffer ? new BytePointer((ByteBuffer) image[0].position(0)) : new BytePointer(new Pointer(image[0].position(0)));
                if (pixelFormat == -1) {
                    if ((depth == 8 || depth == -8) && channels == 3) {
                        pixelFormat = 3;
                    } else if ((depth == 8 || depth == -8) && channels == 1) {
                        pixelFormat = 8;
                    } else if ((depth == 16 || depth == -16) && channels == 1) {
                        pixelFormat = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN) ? 31 : 32;
                    } else if ((depth == 8 || depth == -8) && channels == 4) {
                        pixelFormat = 28;
                    } else {
                        if (depth != 8 && depth != -8 || channels != 2) {
                            throw new Exception("Could not guess pixel format of image: depth=" + depth + ", channels=" + channels);
                        }

                        pixelFormat = 26;
                        step = width;
                    }
                }

                if (this.video_c.pix_fmt() == pixelFormat && this.video_c.width() == width && this.video_c.height() == height) {
                    avcodec.avpicture_fill(new AVPicture(this.picture), data, pixelFormat, width, height);
                    this.picture.linesize(0, step);
                    this.picture.format(pixelFormat);
                    this.picture.width(width);
                    this.picture.height(height);
                } else {
                    this.img_convert_ctx = swscale.sws_getCachedContext(this.img_convert_ctx, width, height, pixelFormat, this.video_c.width(), this.video_c.height(), this.video_c.pix_fmt(), 2, (SwsFilter) null, (SwsFilter) null, (DoublePointer) null);
                    if (this.img_convert_ctx == null) {
                        throw new Exception("sws_getCachedContext() error: Cannot initialize the conversion context.");
                    }

                    avcodec.avpicture_fill(new AVPicture(this.tmp_picture), data, pixelFormat, width, height);
                    avcodec.avpicture_fill(new AVPicture(this.picture), this.picture_buf, this.video_c.pix_fmt(), this.video_c.width(), this.video_c.height());
                    this.tmp_picture.linesize(0, step);
                    this.tmp_picture.format(pixelFormat);
                    this.tmp_picture.width(width);
                    this.tmp_picture.height(height);
                    this.picture.format(this.video_c.pix_fmt());
                    this.picture.width(this.video_c.width());
                    this.picture.height(this.video_c.height());
                    swscale.sws_scale(this.img_convert_ctx, new PointerPointer(this.tmp_picture), this.tmp_picture.linesize(), 0, height, new PointerPointer(this.picture), this.picture.linesize());
                }
            }

            int ret;
            if ((this.oformat.flags() & 32) != 0) {
                if (image == null || image.length == 0) {
                    return false;
                }

                avcodec.av_init_packet(this.video_pkt);
                this.video_pkt.flags(this.video_pkt.flags() | 1);
                this.video_pkt.stream_index(this.video_st.index());
                this.video_pkt.data(new BytePointer(this.picture));
                this.video_pkt.size(Loader.sizeof(AVPicture.class));
            } else {
                avcodec.av_init_packet(this.video_pkt);
                this.video_pkt.data(this.video_outbuf);
                this.video_pkt.size(this.video_outbuf_size);
                this.picture.quality(this.video_c.global_quality());
                if ((ret = avcodec.avcodec_encode_video2(this.video_c, this.video_pkt, image != null && image.length != 0 ? this.picture : null, this.got_video_packet)) < 0) {
                    throw new Exception("avcodec_encode_video2() error " + ret + ": Could not encode video packet.");
                }

                this.picture.pts(this.picture.pts() + 1L);
                if (this.got_video_packet[0] == 0) {
                    return false;
                }

                if (this.video_pkt.pts() != avutil.AV_NOPTS_VALUE) {
                    this.video_pkt.pts(avutil.av_rescale_q(this.video_pkt.pts(), this.video_c.time_base(), this.video_st.time_base()));
                }

                if (this.video_pkt.dts() != avutil.AV_NOPTS_VALUE) {
                    this.video_pkt.dts(avutil.av_rescale_q(this.video_pkt.dts(), this.video_c.time_base(), this.video_st.time_base()));
                }

                this.video_pkt.stream_index(this.video_st.index());
            }

            AVFormatContext step1 = this.oc;
            //实时视频切片
            video_slice_up(SEGMENT_DURATION);
            return image != null ? (this.video_pkt.flags() & 1) != 0 : this.got_video_packet[0] != 0;
        }
    }

    public boolean recordSamples(Buffer... samples) throws Exception {
        return this.recordSamples(0, 0, samples);
    }

    public boolean recordSamples(int sampleRate, int audioChannels, Buffer... samples) throws Exception {
        if (this.audio_st == null) {
            throw new Exception("No audio output stream (Is audioChannels > 0 and has start() been called?)");
        } else {
            if (sampleRate <= 0) {
                sampleRate = this.audio_c.sample_rate();
            }

            if (audioChannels <= 0) {
                audioChannels = this.audio_c.channels();
            }

            int inputSize = samples != null ? samples[0].limit() - samples[0].position() : 0;
            int inputFormat = -1;
            int inputChannels = samples != null && samples.length > 1 ? 1 : audioChannels;
            byte inputDepth = 0;
            int outputFormat = this.audio_c.sample_fmt();
            int outputChannels = this.samples_out.length > 1 ? 1 : this.audio_c.channels();
            int outputDepth = avutil.av_get_bytes_per_sample(outputFormat);
            int inputCount;
            if (samples != null && samples[0] instanceof ByteBuffer) {
                inputFormat = samples.length > 1 ? 5 : 0;
                inputDepth = 1;

                for (inputCount = 0; inputCount < samples.length; ++inputCount) {
                    ByteBuffer var18 = (ByteBuffer) samples[inputCount];
                    if (this.samples_in[inputCount] instanceof BytePointer && this.samples_in[inputCount].capacity() >= inputSize && var18.hasArray()) {
                        ((BytePointer) this.samples_in[inputCount]).position(0).put(var18.array(), var18.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new BytePointer(var18);
                    }
                }
            } else if (samples != null && samples[0] instanceof ShortBuffer) {
                inputFormat = samples.length > 1 ? 6 : 1;
                inputDepth = 2;

                for (inputCount = 0; inputCount < samples.length; ++inputCount) {
                    ShortBuffer var17 = (ShortBuffer) samples[inputCount];
                    if (this.samples_in[inputCount] instanceof ShortPointer && this.samples_in[inputCount].capacity() >= inputSize && var17.hasArray()) {
                        ((ShortPointer) this.samples_in[inputCount]).position(0).put(var17.array(), samples[inputCount].position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new ShortPointer(var17);
                    }
                }
            } else if (samples != null && samples[0] instanceof IntBuffer) {
                inputFormat = samples.length > 1 ? 7 : 2;
                inputDepth = 4;

                for (inputCount = 0; inputCount < samples.length; ++inputCount) {
                    IntBuffer var16 = (IntBuffer) samples[inputCount];
                    if (this.samples_in[inputCount] instanceof IntPointer && this.samples_in[inputCount].capacity() >= inputSize && var16.hasArray()) {
                        ((IntPointer) this.samples_in[inputCount]).position(0).put(var16.array(), samples[inputCount].position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new IntPointer(var16);
                    }
                }
            } else if (samples != null && samples[0] instanceof FloatBuffer) {
                inputFormat = samples.length > 1 ? 8 : 3;
                inputDepth = 4;

                for (inputCount = 0; inputCount < samples.length; ++inputCount) {
                    FloatBuffer var15 = (FloatBuffer) samples[inputCount];
                    if (this.samples_in[inputCount] instanceof FloatPointer && this.samples_in[inputCount].capacity() >= inputSize && var15.hasArray()) {
                        ((FloatPointer) this.samples_in[inputCount]).position(0).put(var15.array(), var15.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new FloatPointer(var15);
                    }
                }
            } else if (samples != null && samples[0] instanceof DoubleBuffer) {
                inputFormat = samples.length > 1 ? 9 : 4;
                inputDepth = 8;

                for (inputCount = 0; inputCount < samples.length; ++inputCount) {
                    DoubleBuffer outputCount = (DoubleBuffer) samples[inputCount];
                    if (this.samples_in[inputCount] instanceof DoublePointer && this.samples_in[inputCount].capacity() >= inputSize && outputCount.hasArray()) {
                        ((DoublePointer) this.samples_in[inputCount]).position(0).put(outputCount.array(), outputCount.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new DoublePointer(outputCount);
                    }
                }
            } else if (samples != null) {
                throw new Exception("Audio samples Buffer has unsupported type: " + samples);
            }

            int ret;
            if (this.samples_convert_ctx == null || this.samples_channels != audioChannels || this.samples_format != inputFormat || this.samples_rate != sampleRate) {
                this.samples_convert_ctx = swresample.swr_alloc_set_opts(this.samples_convert_ctx, this.audio_c.channel_layout(), outputFormat, this.audio_c.sample_rate(), avutil.av_get_default_channel_layout(audioChannels), inputFormat, sampleRate, 0, (Pointer) null);
                if (this.samples_convert_ctx == null) {
                    throw new Exception("swr_alloc_set_opts() error: Cannot allocate the conversion context.");
                }

                if ((ret = swresample.swr_init(this.samples_convert_ctx)) < 0) {
                    throw new Exception("swr_init() error " + ret + ": Cannot initialize the conversion context.");
                }

                this.samples_channels = audioChannels;
                this.samples_format = inputFormat;
                this.samples_rate = sampleRate;
            }

            for (inputCount = 0; samples != null && inputCount < samples.length; ++inputCount) {
                this.samples_in[inputCount].position(this.samples_in[inputCount].position() * inputDepth).limit((this.samples_in[inputCount].position() + inputSize) * inputDepth);
            }

            while (true) {
                int i;
                do {
                    inputCount = samples != null ? (this.samples_in[0].limit() - this.samples_in[0].position()) / (inputChannels * inputDepth) : 0;
                    int var19 = (this.samples_out[0].limit() - this.samples_out[0].position()) / (outputChannels * outputDepth);
                    inputCount = Math.min(inputCount, (var19 * sampleRate + this.audio_c.sample_rate() - 1) / this.audio_c.sample_rate());

                    for (i = 0; samples != null && i < samples.length; ++i) {
                        this.samples_in_ptr.put(i, this.samples_in[i]);
                    }

                    for (i = 0; i < this.samples_out.length; ++i) {
                        this.samples_out_ptr.put(i, this.samples_out[i]);
                    }

                    if ((ret = swresample.swr_convert(this.samples_convert_ctx, this.samples_out_ptr, var19, this.samples_in_ptr, inputCount)) < 0) {
                        throw new Exception("swr_convert() error " + ret + ": Cannot convert audio samples.");
                    }

                    if (ret == 0) {
                        return samples != null ? this.frame.key_frame() != 0 : this.record((AVFrame) null);
                    }

                    for (i = 0; samples != null && i < samples.length; ++i) {
                        this.samples_in[i].position(this.samples_in[i].position() + inputCount * inputChannels * inputDepth);
                    }

                    for (i = 0; i < this.samples_out.length; ++i) {
                        this.samples_out[i].position(this.samples_out[i].position() + ret * outputChannels * outputDepth);
                    }
                }
                while (samples != null && this.samples_out[0].position() < this.samples_out[0].limit());

                this.frame.nb_samples(this.audio_input_frame_size);
                avcodec.avcodec_fill_audio_frame(this.frame, this.audio_c.channels(), outputFormat, this.samples_out[0], this.samples_out[0].limit(), 0);

                for (i = 0; i < this.samples_out.length; ++i) {
                    this.frame.data(i, this.samples_out[i].position(0));
                    this.frame.linesize(i, this.samples_out[i].limit());
                }

                this.frame.quality(this.audio_c.global_quality());
                this.record(this.frame);
            }
        }
    }

    boolean record(AVFrame frame) throws Exception {
        avcodec.av_init_packet(this.audio_pkt);
        this.audio_pkt.data(this.audio_outbuf);
        this.audio_pkt.size(this.audio_outbuf_size);
        int ret;
        if ((ret = avcodec.avcodec_encode_audio2(this.audio_c, this.audio_pkt, frame, this.got_audio_packet)) < 0) {
            throw new Exception("avcodec_encode_audio2() error " + ret + ": Could not encode audio packet.");
        } else {
            if (frame != null) {
                frame.pts(frame.pts() + (long) frame.nb_samples());
            }

            if (this.got_audio_packet[0] != 0) {
                if (this.audio_pkt.pts() != avutil.AV_NOPTS_VALUE) {
                    this.audio_pkt.pts(avutil.av_rescale_q(this.audio_pkt.pts(), this.audio_c.time_base(), this.audio_st.time_base()));
                }

                if (this.audio_pkt.dts() != avutil.AV_NOPTS_VALUE) {
                    this.audio_pkt.dts(avutil.av_rescale_q(this.audio_pkt.dts(), this.audio_c.time_base(), this.audio_st.time_base()));
                }

                this.audio_pkt.flags(this.audio_pkt.flags() | 1);
                this.audio_pkt.stream_index(this.audio_st.index());
                //实时音频切片
                audio_slice_up(SEGMENT_DURATION);
                return true;
            } else {
                return false;
            }
        }
    }

    static {
        try {
            tryLoad();
        } catch (Exception var1) {
            ;
        }

    }
}
