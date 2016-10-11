//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package com.github.crazyorr.ffmpegrecorder.function;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Iterator;
import java.util.Map.Entry;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.ShortPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.swresample;
import org.bytedeco.javacpp.swscale;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avcodec.AVPicture;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVOutputFormat;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.bytedeco.javacpp.avutil.AVFrame;
import org.bytedeco.javacpp.avutil.AVRational;
import org.bytedeco.javacpp.swresample.SwrContext;
import org.bytedeco.javacpp.swscale.SwsContext;
import org.bytedeco.javacpp.swscale.SwsFilter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;

public class FFmpegFrameRecorder extends FrameRecorder {
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

    public static FFmpegFrameRecorderSlice createDefault(File f, int w, int h) throws Exception {
        return new FFmpegFrameRecorderSlice(f, w, h);
    }

    public static FFmpegFrameRecorderSlice createDefault(String f, int w, int h) throws Exception {
        return new FFmpegFrameRecorderSlice(f, w, h);
    }

    public static void tryLoad() throws Exception {
        if(loadingException != null) {
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
                if(var1 instanceof Exception) {
                    throw loadingException = (Exception)var1;
                } else {
                    throw loadingException = new Exception("Failed to load " + FFmpegFrameRecorderSlice.class, var1);
                }
            }
        }
    }

    public FFmpegFrameRecorder(File file, int audioChannels) {
        this((File)file, 0, 0, audioChannels);
    }

    public FFmpegFrameRecorder(String filename, int audioChannels) {
        this((String)filename, 0, 0, audioChannels);
    }

    public FFmpegFrameRecorder(File file, int imageWidth, int imageHeight) {
        this((File)file, imageWidth, imageHeight, 0);
    }

    public FFmpegFrameRecorder(String filename, int imageWidth, int imageHeight) {
        this((String)filename, imageWidth, imageHeight, 0);
    }

    public FFmpegFrameRecorder(File file, int imageWidth, int imageHeight, int audioChannels) {
        this(file.getAbsolutePath(), imageWidth, imageHeight, audioChannels);
    }

    public FFmpegFrameRecorder(String filename, int imageWidth, int imageHeight, int audioChannels) {
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
    }

    public void release() throws Exception {
        Class var1 = avcodec.class;
        synchronized(avcodec.class) {
            this.releaseUnsafe();
        }
    }

    void releaseUnsafe() throws Exception {
        if(this.video_c != null) {
            avcodec.avcodec_close(this.video_c);
            this.video_c = null;
        }

        if(this.audio_c != null) {
            avcodec.avcodec_close(this.audio_c);
            this.audio_c = null;
        }

        if(this.picture_buf != null) {
            avutil.av_free(this.picture_buf);
            this.picture_buf = null;
        }

        if(this.picture != null) {
            avutil.av_frame_free(this.picture);
            this.picture = null;
        }

        if(this.tmp_picture != null) {
            avutil.av_frame_free(this.tmp_picture);
            this.tmp_picture = null;
        }

        if(this.video_outbuf != null) {
            avutil.av_free(this.video_outbuf);
            this.video_outbuf = null;
        }

        if(this.frame != null) {
            avutil.av_frame_free(this.frame);
            this.frame = null;
        }

        int nb_streams;
        if(this.samples_out != null) {
            for(nb_streams = 0; nb_streams < this.samples_out.length; ++nb_streams) {
                avutil.av_free(this.samples_out[nb_streams].position(0));
            }

            this.samples_out = null;
        }

        if(this.audio_outbuf != null) {
            avutil.av_free(this.audio_outbuf);
            this.audio_outbuf = null;
        }

        if(this.video_st != null && this.video_st.metadata() != null) {
            avutil.av_dict_free(this.video_st.metadata());
            this.video_st.metadata((AVDictionary)null);
        }

        if(this.audio_st != null && this.audio_st.metadata() != null) {
            avutil.av_dict_free(this.audio_st.metadata());
            this.audio_st.metadata((AVDictionary)null);
        }

        this.video_st = null;
        this.audio_st = null;
        if(this.oc != null && !this.oc.isNull()) {
            if((this.oformat.flags() & 1) == 0) {
                avformat.avio_close(this.oc.pb());
            }

            nb_streams = this.oc.nb_streams();

            for(int i = 0; i < nb_streams; ++i) {
                avutil.av_free(this.oc.streams(i).codec());
                avutil.av_free(this.oc.streams(i));
            }

            if(this.oc.metadata() != null) {
                avutil.av_dict_free(this.oc.metadata());
                this.oc.metadata((AVDictionary)null);
            }

            avutil.av_free(this.oc);
            this.oc = null;
        }

        if(this.img_convert_ctx != null) {
            swscale.sws_freeContext(this.img_convert_ctx);
            this.img_convert_ctx = null;
        }

        if(this.samples_convert_ctx != null) {
            swresample.swr_free(this.samples_convert_ctx);
            this.samples_convert_ctx = null;
        }

    }

    protected void finalize() throws Throwable {
        super.finalize();
        this.release();
    }

    public int getFrameNumber() {
        return this.picture == null?super.getFrameNumber():(int)this.picture.pts();
    }

    public void setFrameNumber(int frameNumber) {
        if(this.picture == null) {
            super.setFrameNumber(frameNumber);
        } else {
            this.picture.pts((long)frameNumber);
        }

    }

    public long getTimestamp() {
        return Math.round((double)((long)this.getFrameNumber() * 1000000L) / this.getFrameRate());
    }

    public void setTimestamp(long timestamp) {
        this.setFrameNumber((int)Math.round((double)timestamp * this.getFrameRate() / 1000000.0D));
    }

    public void start() throws Exception {
        Class var1 = avcodec.class;
        synchronized(avcodec.class) {
            this.startUnsafe();
        }
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
        String format_name = this.format != null && this.format.length() != 0?this.format:null;
        if((this.oformat = avformat.av_guess_format(format_name, this.filename, (String)null)) == null) {
            int options = this.filename.indexOf("://");
            if(options > 0) {
                format_name = this.filename.substring(0, options);
            }

            if((this.oformat = avformat.av_guess_format(format_name, this.filename, (String)null)) == null) {
                throw new Exception("av_guess_format() error: Could not guess output format for \"" + this.filename + "\" and " + this.format + " format.");
            }
        }

        format_name = this.oformat.name().getString();
        if((this.oc = avformat.avformat_alloc_context()) == null) {
            throw new Exception("avformat_alloc_context() error: Could not allocate format context");
        } else {
            this.oc.oformat(this.oformat);
            this.oc.filename().putString(this.filename);
            int e;
            if(this.imageWidth > 0 && this.imageHeight > 0) {
                if(this.videoCodec != 0) {
                    this.oformat.video_codec(this.videoCodec);
                } else if("flv".equals(format_name)) {
                    this.oformat.video_codec(22);
                } else if("mp4".equals(format_name)) {
                    this.oformat.video_codec(13);
                } else if("3gp".equals(format_name)) {
                    this.oformat.video_codec(5);
                } else if("avi".equals(format_name)) {
                    this.oformat.video_codec(26);
                }

                if((this.video_codec = avcodec.avcodec_find_encoder_by_name(this.videoCodecName)) == null && (this.video_codec = avcodec.avcodec_find_encoder(this.oformat.video_codec())) == null) {
                    this.release();
                    throw new Exception("avcodec_find_encoder() error: Video codec not found.");
                }

                this.oformat.video_codec(this.video_codec.id());
                AVRational var9 = avutil.av_d2q(this.frameRate, 1001000);
                AVRational metadata = this.video_codec.supported_framerates();
                if(metadata != null) {
                    e = avutil.av_find_nearest_q_idx(var9, metadata);
                    var9 = metadata.position(e);
                }

                if((this.video_st = avformat.avformat_new_stream(this.oc, this.video_codec)) == null) {
                    this.release();
                    throw new Exception("avformat_new_stream() error: Could not allocate video stream.");
                }

                this.video_c = this.video_st.codec();
                this.video_c.codec_id(this.oformat.video_codec());
                this.video_c.codec_type(0);
                this.video_c.bit_rate(this.videoBitrate);
                this.video_c.width((this.imageWidth + 15) / 16 * 16);
                this.video_c.height(this.imageHeight);
                if(this.aspectRatio > 0.0D) {
                    AVRational var13 = avutil.av_d2q(this.aspectRatio, 255);
                    this.video_c.sample_aspect_ratio(var13);
                    this.video_st.sample_aspect_ratio(var13);
                }

                this.video_c.time_base(avutil.av_inv_q(var9));
                this.video_st.time_base(avutil.av_inv_q(var9));
                if(this.gopSize >= 0) {
                    this.video_c.gop_size(this.gopSize);
                }

                if(this.videoQuality >= 0.0D) {
                    this.video_c.flags(this.video_c.flags() | 2);
                    this.video_c.global_quality((int)Math.round(118.0D * this.videoQuality));
                }

                if(this.pixelFormat != -1) {
                    this.video_c.pix_fmt(this.pixelFormat);
                } else if(this.video_c.codec_id() != 14 && this.video_c.codec_id() != 62 && this.video_c.codec_id() != 26 && this.video_c.codec_id() != 34) {
                    this.video_c.pix_fmt(0);
                } else {
                    this.video_c.pix_fmt(avutil.AV_PIX_FMT_RGB32);
                }

                if(this.video_c.codec_id() == 2) {
                    this.video_c.max_b_frames(2);
                } else if(this.video_c.codec_id() == 1) {
                    this.video_c.mb_decision(2);
                } else if(this.video_c.codec_id() == 5) {
                    if(this.imageWidth <= 128 && this.imageHeight <= 96) {
                        this.video_c.width(128).height(96);
                    } else if(this.imageWidth <= 176 && this.imageHeight <= 144) {
                        this.video_c.width(176).height(144);
                    } else if(this.imageWidth <= 352 && this.imageHeight <= 288) {
                        this.video_c.width(352).height(288);
                    } else if(this.imageWidth <= 704 && this.imageHeight <= 576) {
                        this.video_c.width(704).height(576);
                    } else {
                        this.video_c.width(1408).height(1152);
                    }
                } else if(this.video_c.codec_id() == 28) {
                    this.video_c.profile(578);
                }

                if((this.oformat.flags() & 64) != 0) {
                    this.video_c.flags(this.video_c.flags() | 4194304);
                }

                if((this.video_codec.capabilities() & 512) != 0) {
                    this.video_c.strict_std_compliance(-2);
                }
            }

            int var10;
            if(this.audioChannels > 0 && this.audioBitrate > 0 && this.sampleRate > 0) {
                if(this.audioCodec != 0) {
                    this.oformat.audio_codec(this.audioCodec);
                } else if(!"flv".equals(format_name) && !"mp4".equals(format_name) && !"3gp".equals(format_name)) {
                    if("avi".equals(format_name)) {
                        this.oformat.audio_codec(65536);
                    }
                } else {
                    this.oformat.audio_codec(86018);
                }

                if((this.audio_codec = avcodec.avcodec_find_encoder_by_name(this.audioCodecName)) == null && (this.audio_codec = avcodec.avcodec_find_encoder(this.oformat.audio_codec())) == null) {
                    this.release();
                    throw new Exception("avcodec_find_encoder() error: Audio codec not found.");
                }

                this.oformat.audio_codec(this.audio_codec.id());
                if((this.audio_st = avformat.avformat_new_stream(this.oc, this.audio_codec)) == null) {
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
                if(this.sampleFormat != -1) {
                    this.audio_c.sample_fmt(this.sampleFormat);
                } else {
                    this.audio_c.sample_fmt(8);
                    IntPointer var11 = this.audio_c.codec().sample_fmts();

                    for(var10 = 0; var11.get(var10) != -1; ++var10) {
                        if(var11.get(var10) == 1) {
                            this.audio_c.sample_fmt(1);
                            break;
                        }
                    }
                }

                this.audio_c.time_base().num(1).den(this.sampleRate);
                this.audio_st.time_base().num(1).den(this.sampleRate);
                switch(this.audio_c.sample_fmt()) {
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

                if(this.audioQuality >= 0.0D) {
                    this.audio_c.flags(this.audio_c.flags() | 2);
                    this.audio_c.global_quality((int)Math.round(118.0D * this.audioQuality));
                }

                if((this.oformat.flags() & 64) != 0) {
                    this.audio_c.flags(this.audio_c.flags() | 4194304);
                }

                if((this.audio_codec.capabilities() & 512) != 0) {
                    this.audio_c.strict_std_compliance(-2);
                }
            }

            avformat.av_dump_format(this.oc, 0, this.filename, 1);
            int ret;
            AVDictionary var12;
            Iterator var15;
            Entry var17;
            if(this.video_st != null) {
                var12 = new AVDictionary((Pointer)null);
                if(this.videoQuality >= 0.0D) {
                    avutil.av_dict_set(var12, "crf", "" + this.videoQuality, 0);
                }

                var15 = this.videoOptions.entrySet().iterator();

                while(var15.hasNext()) {
                    var17 = (Entry)var15.next();
                    avutil.av_dict_set(var12, (String)var17.getKey(), (String)var17.getValue(), 0);
                }

                if((ret = avcodec.avcodec_open2(this.video_c, this.video_codec, var12)) < 0) {
                    this.release();
                    throw new Exception("avcodec_open2() error " + ret + ": Could not open video codec.");
                }

                avutil.av_dict_free(var12);
                this.video_outbuf = null;
                if((this.oformat.flags() & 32) == 0) {
                    this.video_outbuf_size = Math.max(262144, 8 * this.video_c.width() * this.video_c.height());
                    this.video_outbuf = new BytePointer(avutil.av_malloc((long)this.video_outbuf_size));
                }

                if((this.picture = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate picture.");
                }

                this.picture.pts(0L);
                var10 = avcodec.avpicture_get_size(this.video_c.pix_fmt(), this.video_c.width(), this.video_c.height());
                if((this.picture_buf = new BytePointer(avutil.av_malloc((long)var10))).isNull()) {
                    this.release();
                    throw new Exception("av_malloc() error: Could not allocate picture buffer.");
                }

                if((this.tmp_picture = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate temporary picture.");
                }

                AVDictionary var16 = new AVDictionary((Pointer)null);
                Iterator e1 = this.videoMetadata.entrySet().iterator();

                while(e1.hasNext()) {
                    Entry e2 = (Entry)e1.next();
                    avutil.av_dict_set(var16, (String)e2.getKey(), (String)e2.getValue(), 0);
                }

                this.video_st.metadata(var16);
            }

            if(this.audio_st != null) {
                var12 = new AVDictionary((Pointer)null);
                if(this.audioQuality >= 0.0D) {
                    avutil.av_dict_set(var12, "crf", "" + this.audioQuality, 0);
                }

                var15 = this.audioOptions.entrySet().iterator();

                while(var15.hasNext()) {
                    var17 = (Entry)var15.next();
                    avutil.av_dict_set(var12, (String)var17.getKey(), (String)var17.getValue(), 0);
                }

                if((ret = avcodec.avcodec_open2(this.audio_c, this.audio_codec, var12)) < 0) {
                    this.release();
                    throw new Exception("avcodec_open2() error " + ret + ": Could not open audio codec.");
                }

                avutil.av_dict_free(var12);
                this.audio_outbuf_size = 262144;
                this.audio_outbuf = new BytePointer(avutil.av_malloc((long)this.audio_outbuf_size));
                if(this.audio_c.frame_size() <= 1) {
                    this.audio_outbuf_size = 16384;
                    this.audio_input_frame_size = this.audio_outbuf_size / this.audio_c.channels();
                    switch(this.audio_c.codec_id()) {
                        case 65536:
                        case 65537:
                        case 65538:
                        case 65539:
                            this.audio_input_frame_size >>= 1;
                    }
                } else {
                    this.audio_input_frame_size = this.audio_c.frame_size();
                }

                var10 = avutil.av_sample_fmt_is_planar(this.audio_c.sample_fmt()) != 0?this.audio_c.channels():1;
                e = avutil.av_samples_get_buffer_size((IntPointer)null, this.audio_c.channels(), this.audio_input_frame_size, this.audio_c.sample_fmt(), 1) / var10;
                this.samples_out = new BytePointer[var10];

                for(int var19 = 0; var19 < this.samples_out.length; ++var19) {
                    this.samples_out[var19] = (new BytePointer(avutil.av_malloc((long)e))).capacity(e);
                }

                this.samples_in = new Pointer[8];
                this.samples_in_ptr = new PointerPointer(8);
                this.samples_out_ptr = new PointerPointer(8);
                if((this.frame = avutil.av_frame_alloc()) == null) {
                    this.release();
                    throw new Exception("av_frame_alloc() error: Could not allocate audio frame.");
                }

                this.frame.pts(0L);
                AVDictionary var23 = new AVDictionary((Pointer)null);
                Iterator var22 = this.audioMetadata.entrySet().iterator();

                while(var22.hasNext()) {
                    Entry e3 = (Entry)var22.next();
                    avutil.av_dict_set(var23, (String)e3.getKey(), (String)e3.getValue(), 0);
                }

                this.audio_st.metadata(var23);
            }

            if((this.oformat.flags() & 1) == 0) {
                AVIOContext var14 = new AVIOContext((Pointer)null);
                if((ret = avformat.avio_open(var14, this.filename, 2)) < 0) {
                    this.release();
                    throw new Exception("avio_open error() error " + ret + ": Could not open \'" + this.filename + "\'");
                }

                this.oc.pb(var14);
            }

            var12 = new AVDictionary((Pointer)null);
            var15 = this.options.entrySet().iterator();

            while(var15.hasNext()) {
                var17 = (Entry)var15.next();
                avutil.av_dict_set(var12, (String)var17.getKey(), (String)var17.getValue(), 0);
            }

            AVDictionary var18 = new AVDictionary((Pointer)null);
            Iterator var20 = this.metadata.entrySet().iterator();

            while(var20.hasNext()) {
                Entry var21 = (Entry)var20.next();
                avutil.av_dict_set(var18, (String)var21.getKey(), (String)var21.getValue(), 0);
            }

            avformat.avformat_write_header(this.oc.metadata(var18), var12);
            avutil.av_dict_free(var12);
        }
    }

    public void stop() throws Exception {
        if(this.oc != null) {
            try {
                while(this.video_st != null && this.recordImage(0, 0, 0, 0, 0, -1, (Buffer[])null)) {
                    ;
                }

                while(true) {
                    if(this.audio_st == null || !this.recordSamples(0, 0, (Buffer[])null)) {
                        if(this.interleaved && this.video_st != null && this.audio_st != null) {
                            avformat.av_interleaved_write_frame(this.oc, (AVPacket)null);
                        } else {
                            avformat.av_write_frame(this.oc, (AVPacket)null);
                        }

                        avformat.av_write_trailer(this.oc);
                        break;
                    }
                }
            } finally {
                this.release();
            }
        }

    }

    public void record(Frame frame) throws Exception {
        this.record(frame, -1);
    }

    public void record(Frame frame, int pixelFormat) throws Exception {
        if(frame != null && (frame.image != null || frame.samples != null)) {
            if(frame.image != null) {
                frame.keyFrame = this.recordImage(frame.imageWidth, frame.imageHeight, frame.imageDepth, frame.imageChannels, frame.imageStride, pixelFormat, frame.image);
            }

            if(frame.samples != null) {
                frame.keyFrame = this.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
            }
        } else {
            this.recordImage(0, 0, 0, 0, 0, pixelFormat, (Buffer[])null);
        }

    }

    public boolean recordImage(int width, int height, int depth, int channels, int stride, int pixelFormat, Buffer... image) throws Exception {
        if(this.video_st == null) {
            throw new Exception("No video output stream (Is imageWidth > 0 && imageHeight > 0 and has start() been called?)");
        } else {
            if(image != null && image.length != 0) {
                int step = stride * Math.abs(depth) / 8;
                BytePointer data = image[0] instanceof ByteBuffer?new BytePointer((ByteBuffer)image[0].position(0)):new BytePointer(new Pointer(image[0].position(0)));
                if(pixelFormat == -1) {
                    if((depth == 8 || depth == -8) && channels == 3) {
                        pixelFormat = 3;
                    } else if((depth == 8 || depth == -8) && channels == 1) {
                        pixelFormat = 8;
                    } else if((depth == 16 || depth == -16) && channels == 1) {
                        pixelFormat = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)?31:32;
                    } else if((depth == 8 || depth == -8) && channels == 4) {
                        pixelFormat = 28;
                    } else {
                        if(depth != 8 && depth != -8 || channels != 2) {
                            throw new Exception("Could not guess pixel format of image: depth=" + depth + ", channels=" + channels);
                        }

                        pixelFormat = 26;
                        step = width;
                    }
                }

                if(this.video_c.pix_fmt() == pixelFormat && this.video_c.width() == width && this.video_c.height() == height) {
                    avcodec.avpicture_fill(new AVPicture(this.picture), data, pixelFormat, width, height);
                    this.picture.linesize(0, step);
                    this.picture.format(pixelFormat);
                    this.picture.width(width);
                    this.picture.height(height);
                } else {
                    this.img_convert_ctx = swscale.sws_getCachedContext(this.img_convert_ctx, width, height, pixelFormat, this.video_c.width(), this.video_c.height(), this.video_c.pix_fmt(), 2, (SwsFilter)null, (SwsFilter)null, (DoublePointer)null);
                    if(this.img_convert_ctx == null) {
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
            if((this.oformat.flags() & 32) != 0) {
                if(image == null || image.length == 0) {
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
                if((ret = avcodec.avcodec_encode_video2(this.video_c, this.video_pkt, image != null && image.length != 0?this.picture:null, this.got_video_packet)) < 0) {
                    throw new Exception("avcodec_encode_video2() error " + ret + ": Could not encode video packet.");
                }

                this.picture.pts(this.picture.pts() + 1L);
                if(this.got_video_packet[0] == 0) {
                    return false;
                }

                if(this.video_pkt.pts() != avutil.AV_NOPTS_VALUE) {
                    this.video_pkt.pts(avutil.av_rescale_q(this.video_pkt.pts(), this.video_c.time_base(), this.video_st.time_base()));
                }

                if(this.video_pkt.dts() != avutil.AV_NOPTS_VALUE) {
                    this.video_pkt.dts(avutil.av_rescale_q(this.video_pkt.dts(), this.video_c.time_base(), this.video_st.time_base()));
                }

                this.video_pkt.stream_index(this.video_st.index());
            }

            AVFormatContext step1 = this.oc;
            synchronized(this.oc) {
                if(this.interleaved && this.audio_st != null) {
                    if((ret = avformat.av_interleaved_write_frame(this.oc, this.video_pkt)) < 0) {
                        throw new Exception("av_interleaved_write_frame() error " + ret + " while writing interleaved video frame.");
                    }
                } else if((ret = avformat.av_write_frame(this.oc, this.video_pkt)) < 0) {
                    throw new Exception("av_write_frame() error " + ret + " while writing video frame.");
                }
            }

            return image != null?(this.video_pkt.flags() & 1) != 0:this.got_video_packet[0] != 0;
        }
    }

    public boolean recordSamples(Buffer... samples) throws Exception {
        return this.recordSamples(0, 0, samples);
    }

    public boolean recordSamples(int sampleRate, int audioChannels, Buffer... samples) throws Exception {
        if(this.audio_st == null) {
            throw new Exception("No audio output stream (Is audioChannels > 0 and has start() been called?)");
        } else {
            if(sampleRate <= 0) {
                sampleRate = this.audio_c.sample_rate();
            }

            if(audioChannels <= 0) {
                audioChannels = this.audio_c.channels();
            }

            int inputSize = samples != null?samples[0].limit() - samples[0].position():0;
            int inputFormat = -1;
            int inputChannels = samples != null && samples.length > 1?1:audioChannels;
            byte inputDepth = 0;
            int outputFormat = this.audio_c.sample_fmt();
            int outputChannels = this.samples_out.length > 1?1:this.audio_c.channels();
            int outputDepth = avutil.av_get_bytes_per_sample(outputFormat);
            int inputCount;
            if(samples != null && samples[0] instanceof ByteBuffer) {
                inputFormat = samples.length > 1?5:0;
                inputDepth = 1;

                for(inputCount = 0; inputCount < samples.length; ++inputCount) {
                    ByteBuffer var18 = (ByteBuffer)samples[inputCount];
                    if(this.samples_in[inputCount] instanceof BytePointer && this.samples_in[inputCount].capacity() >= inputSize && var18.hasArray()) {
                        ((BytePointer)this.samples_in[inputCount]).position(0).put(var18.array(), var18.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new BytePointer(var18);
                    }
                }
            } else if(samples != null && samples[0] instanceof ShortBuffer) {
                inputFormat = samples.length > 1?6:1;
                inputDepth = 2;

                for(inputCount = 0; inputCount < samples.length; ++inputCount) {
                    ShortBuffer var16 = (ShortBuffer)samples[inputCount];
                    if(this.samples_in[inputCount] instanceof ShortPointer && this.samples_in[inputCount].capacity() >= inputSize && var16.hasArray()) {
                        ((ShortPointer)this.samples_in[inputCount]).position(0).put(var16.array(), samples[inputCount].position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new ShortPointer(var16);
                    }
                }
            } else if(samples != null && samples[0] instanceof IntBuffer) {
                inputFormat = samples.length > 1?7:2;
                inputDepth = 4;

                for(inputCount = 0; inputCount < samples.length; ++inputCount) {
                    IntBuffer var15 = (IntBuffer)samples[inputCount];
                    if(this.samples_in[inputCount] instanceof IntPointer && this.samples_in[inputCount].capacity() >= inputSize && var15.hasArray()) {
                        ((IntPointer)this.samples_in[inputCount]).position(0).put(var15.array(), samples[inputCount].position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new IntPointer(var15);
                    }
                }
            } else if(samples != null && samples[0] instanceof FloatBuffer) {
                inputFormat = samples.length > 1?8:3;
                inputDepth = 4;

                for(inputCount = 0; inputCount < samples.length; ++inputCount) {
                    FloatBuffer var17 = (FloatBuffer)samples[inputCount];
                    if(this.samples_in[inputCount] instanceof FloatPointer && this.samples_in[inputCount].capacity() >= inputSize && var17.hasArray()) {
                        ((FloatPointer)this.samples_in[inputCount]).position(0).put(var17.array(), var17.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new FloatPointer(var17);
                    }
                }
            } else if(samples != null && samples[0] instanceof DoubleBuffer) {
                inputFormat = samples.length > 1?9:4;
                inputDepth = 8;

                for(inputCount = 0; inputCount < samples.length; ++inputCount) {
                    DoubleBuffer outputCount = (DoubleBuffer)samples[inputCount];
                    if(this.samples_in[inputCount] instanceof DoublePointer && this.samples_in[inputCount].capacity() >= inputSize && outputCount.hasArray()) {
                        ((DoublePointer)this.samples_in[inputCount]).position(0).put(outputCount.array(), outputCount.position(), inputSize);
                    } else {
                        this.samples_in[inputCount] = new DoublePointer(outputCount);
                    }
                }
            } else if(samples != null) {
                throw new Exception("Audio samples Buffer has unsupported type: " + samples);
            }

            int ret;
            if(this.samples_convert_ctx == null || this.samples_channels != audioChannels || this.samples_format != inputFormat || this.samples_rate != sampleRate) {
                this.samples_convert_ctx = swresample.swr_alloc_set_opts(this.samples_convert_ctx, this.audio_c.channel_layout(), outputFormat, this.audio_c.sample_rate(), avutil.av_get_default_channel_layout(audioChannels), inputFormat, sampleRate, 0, (Pointer)null);
                if(this.samples_convert_ctx == null) {
                    throw new Exception("swr_alloc_set_opts() error: Cannot allocate the conversion context.");
                }

                if((ret = swresample.swr_init(this.samples_convert_ctx)) < 0) {
                    throw new Exception("swr_init() error " + ret + ": Cannot initialize the conversion context.");
                }

                this.samples_channels = audioChannels;
                this.samples_format = inputFormat;
                this.samples_rate = sampleRate;
            }

            for(inputCount = 0; samples != null && inputCount < samples.length; ++inputCount) {
                this.samples_in[inputCount].position(this.samples_in[inputCount].position() * inputDepth).limit((this.samples_in[inputCount].position() + inputSize) * inputDepth);
            }

            while(true) {
                int i;
                do {
                    inputCount = samples != null?(this.samples_in[0].limit() - this.samples_in[0].position()) / (inputChannels * inputDepth):0;
                    int var19 = (this.samples_out[0].limit() - this.samples_out[0].position()) / (outputChannels * outputDepth);
                    inputCount = Math.min(inputCount, (var19 * sampleRate + this.audio_c.sample_rate() - 1) / this.audio_c.sample_rate());

                    for(i = 0; samples != null && i < samples.length; ++i) {
                        this.samples_in_ptr.put(i, this.samples_in[i]);
                    }

                    for(i = 0; i < this.samples_out.length; ++i) {
                        this.samples_out_ptr.put(i, this.samples_out[i]);
                    }

                    if((ret = swresample.swr_convert(this.samples_convert_ctx, this.samples_out_ptr, var19, this.samples_in_ptr, inputCount)) < 0) {
                        throw new Exception("swr_convert() error " + ret + ": Cannot convert audio samples.");
                    }

                    if(ret == 0) {
                        return samples != null?this.frame.key_frame() != 0:this.record((AVFrame)null);
                    }

                    for(i = 0; samples != null && i < samples.length; ++i) {
                        this.samples_in[i].position(this.samples_in[i].position() + inputCount * inputChannels * inputDepth);
                    }

                    for(i = 0; i < this.samples_out.length; ++i) {
                        this.samples_out[i].position(this.samples_out[i].position() + ret * outputChannels * outputDepth);
                    }
                } while(samples != null && this.samples_out[0].position() < this.samples_out[0].limit());

                this.frame.nb_samples(this.audio_input_frame_size);
                avcodec.avcodec_fill_audio_frame(this.frame, this.audio_c.channels(), outputFormat, this.samples_out[0], this.samples_out[0].limit(), 0);

                for(i = 0; i < this.samples_out.length; ++i) {
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
        if((ret = avcodec.avcodec_encode_audio2(this.audio_c, this.audio_pkt, frame, this.got_audio_packet)) < 0) {
            throw new Exception("avcodec_encode_audio2() error " + ret + ": Could not encode audio packet.");
        } else {
            if(frame != null) {
                frame.pts(frame.pts() + (long)frame.nb_samples());
            }

            if(this.got_audio_packet[0] != 0) {
                if(this.audio_pkt.pts() != avutil.AV_NOPTS_VALUE) {
                    this.audio_pkt.pts(avutil.av_rescale_q(this.audio_pkt.pts(), this.audio_c.time_base(), this.audio_st.time_base()));
                }

                if(this.audio_pkt.dts() != avutil.AV_NOPTS_VALUE) {
                    this.audio_pkt.dts(avutil.av_rescale_q(this.audio_pkt.dts(), this.audio_c.time_base(), this.audio_st.time_base()));
                }

                this.audio_pkt.flags(this.audio_pkt.flags() | 1);
                this.audio_pkt.stream_index(this.audio_st.index());
                AVFormatContext var3 = this.oc;
                synchronized(this.oc) {
                    if(this.interleaved && this.video_st != null) {
                        if((ret = avformat.av_interleaved_write_frame(this.oc, this.audio_pkt)) < 0) {
                            throw new Exception("av_interleaved_write_frame() error " + ret + " while writing interleaved audio frame.");
                        }
                    } else if((ret = avformat.av_write_frame(this.oc, this.audio_pkt)) < 0) {
                        throw new Exception("av_write_frame() error " + ret + " while writing audio frame.");
                    }

                    return true;
                }
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
