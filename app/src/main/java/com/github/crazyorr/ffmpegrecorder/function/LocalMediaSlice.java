package com.github.crazyorr.ffmpegrecorder.function;

import android.os.Environment;
import android.util.Log;

import com.github.crazyorr.ffmpegrecorder.FFmpegRecordActivity;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 本地媒体文件切片 Created by 波仔糕 on 2016/9/1 0001.
 */
public class LocalMediaSlice {
    private avformat.AVFormatContext icodec = new avformat.AVFormatContext(null);
    private avformat.AVStream ovideo_st = new avformat.AVStream(null);
    private avformat.AVFormatContext ocodec = new avformat.AVFormatContext();
    private int nRet;
    private String TAG = "TAG";
    private avformat.AVFormatContext avFormatContext = new avformat.AVFormatContext(null);
    private avformat.AVStream oaudio_st = new avformat.AVStream();
    private int video_stream_idx = -1;
    private int audio_stream_idx = -1;
    private avcodec.AVBitStreamFilterContext vbsf_h264_toannexb = null;
    private avcodec.AVBitStreamFilterContext vbsf_aac_adtstoasc = null;
    private int IsAACCodes = 0;
    //packet 中的ID ，如果先加入音频 pocket 则音频是 0  视频是1，否则相反(影响add_out_stream顺序)
    private final int AUDIO_ID = 0;
    private final int VIDEO_ID = 1;
    //在磁盘上一共最多存储多少个分片
    private final int NUM_SEGMENTS = 50;
    //生成目录
    private String URL_PREFIX;
    //生成的m3u8文件名
    private final String M3U8_FILE_NAME = "ZWG_TEST.m3u8";
    //切割文件的前缀
    private final String OUTPUT_PREFIX = "ZWG_TEST";
    //m3u8 param
    private int m_output_index = 1;//生成的切片文件顺序编号
    //声称切片的文件名字
    private BytePointer m_output_file_name = null;
    private String video_type = "";
    private byte[] szError;

    private enum AVMediaType {
        AVMEDIA_TYPE_VIDEO,
        AVMEDIA_TYPE_AUDIO
    }


    /**
     * 根据本地视频文件进行TS切片 Created by 波仔糕 on 2016/9/1 0001.
     */
    public void slice_ts_by_LocalMediaFile(String filePath, int hls_time) throws FrameRecorder.Exception {
        //切片存放路径
        URL_PREFIX = Environment.getExternalStorageDirectory().getPath() + "/local_media_slice/";
        File path = new File(URL_PREFIX);
        if (!path.exists()) {
            try {
                //如果不存在就创建该文件目录
                path.mkdirs();
            } catch (Exception e) {
            }
        }
        DeleteFiles(path);
        avformat.av_register_all();
        avformat.avformat_network_init();
        avcodec.avcodec_register_all();
        init_demux(icodec, new BytePointer(filePath));
        slice_up(hls_time);
        uinit_demux();
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


    /**
     * 初始化分流配置 Created by 波仔糕 on 2016/9/1 0001.
     */
    private int init_demux(avformat.AVFormatContext iframe_c, BytePointer Filename) {
        int i = 0;
        String tmp = Filename.getString();
        nRet = avformat.avformat_open_input(iframe_c, Filename, null, null);
        if (nRet != 0) {
            Log.e(TAG, "Call avformat_open_input function failed!\n");
            return 0;
        }
        if (avformat.avformat_find_stream_info(iframe_c, (avutil.AVDictionary) null) < 0) {
            Log.e(TAG, "Call av_find_stream_info function failed!\n");
            return 0;
        }
        //输出视频信息
        avformat.av_dump_format(iframe_c, -1, Filename, 0);
        //添加音频信息到输出context
        for (i = 0; i < iframe_c.nb_streams(); i++) {
            if (iframe_c.streams(i).codec().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
                video_stream_idx = i;
            } else if (iframe_c.streams(i).codec().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                audio_stream_idx = i;
            }
        }
        Log.e("TAG", icodec.iformat().name().getString());
        if (icodec.iformat().name().getString().contains("flv") ||
                icodec.iformat().name().getString().contains("mp4") ||
                icodec.iformat().name().getString().contains("mov")) {
            if (icodec.streams(video_stream_idx).codec().codec_id() == avcodec.AV_CODEC_ID_H264)  //AV_CODEC_ID_H264
            {
                //这里注意："h264_mp4toannexb",一定是这个字符串，无论是 flv，mp4，mov格式
                vbsf_h264_toannexb = avcodec.av_bitstream_filter_init("h264_mp4toannexb");
            }
            if (icodec.streams(audio_stream_idx).codec().codec_id() == avcodec.AV_CODEC_ID_AAC) //AV_CODEC_ID_AAC
            {
                IsAACCodes = 1;
            }
        }

        return 1;
    }


    /**
     * 切片函数 add by 波仔糕 on 2016/9/1
     */
    private void slice_up(int SEGMENT_DURATION) throws FrameRecorder.Exception {
        boolean write_flag = true;
        int first_segment = 1;     //第一个分片的标号
        int last_segment = 0;      //最后一个分片标号
        int decode_done = 0;       //文件是否读取完成
        int remove_file = 0;       //是否要移除文件（写在磁盘的分片已经达到最大）
        String remove_filename = "";    //要从磁盘上删除的文件名称
        double prev_segment_time = 0;//上一个分片时间
        int ret = 0;
        int[] actual_segment_durations; //各个分片文件实际的长度
        actual_segment_durations = new int[1024];

        //填写第一个输出文件名称
        m_output_file_name = new BytePointer(URL_PREFIX + OUTPUT_PREFIX + "-" + m_output_index + ".ts");
        m_output_index++;
        //****************************************创建输出文件（写头部）
        init_mux();
        write_flag = !write_index_file(first_segment, last_segment, false, actual_segment_durations, SEGMENT_DURATION);
        do {
            int current_segment_duration;
            double segment_time = prev_segment_time;
            avcodec.AVPacket packet = new avcodec.AVPacket();
            avcodec.av_init_packet(packet);
            //本地流
            decode_done = avformat.av_read_frame(icodec, packet);

            if (decode_done < 0) {
                break;
            }

            if (avcodec.av_dup_packet(packet) < 0) {
                Log.e(TAG, "Could not duplicate packet");
                avcodec.av_free_packet(packet);
                break;
            }

            if (packet.stream_index() == video_stream_idx) {
                Log.e("TAG", "执行标记0");
                segment_time = packet.pts() * avutil.av_q2d(icodec.streams(video_stream_idx).time_base());
            } else if (video_stream_idx < 0) {
                Log.e("TAG", "执行标记1");
                segment_time = packet.pts() * avutil.av_q2d(icodec.streams(audio_stream_idx).time_base());
            } else {
                segment_time = prev_segment_time;
                Log.e("TAG", "执行标记2" + "index=" + packet.stream_index());
            }

            //这里是为了纠错，有文件pts为不可用值
            if (packet.pts() < packet.dts()) {
                packet.pts(packet.dts());
            }

            //视频
            if (packet.stream_index() == video_stream_idx) {
                /*if (vbsf_h264_toannexb != null) {
                    avcodec.AVPacket filteredPacket = packet;
                    int a = avcodec.av_bitstream_filter_filter(vbsf_h264_toannexb,
                            ovideo_st.codec(), (BytePointer) null, filteredPacket.data(), new IntPointer(filteredPacket.size()), packet.data(), packet.size(), packet.flags() & avcodec.AV_PKT_FLAG_KEY);
                    if (a > 0) {
                        Log.e("TAG","a>0");
                        avcodec.av_free_packet(packet);
                        packet.pts(filteredPacket.pts());
                        packet.dts(filteredPacket.dts());
                        packet.duration(filteredPacket.duration());
                        packet.flags(filteredPacket.flags());
                        packet.stream_index(filteredPacket.stream_index());
                        packet.data(filteredPacket.data());
                        packet.size(filteredPacket.size());
                    } else if (a < 0) {
                        Log.e("TAG","a<0");
                        avcodec.av_free_packet(packet);
                    }
                }*/
                packet.pts(avutil.av_rescale_q_rnd(packet.pts(), icodec.streams(video_stream_idx)
                        .time_base(), ovideo_st.time_base(), avutil.AV_ROUND_NEAR_INF));
                packet.dts(avutil.av_rescale_q_rnd(packet.dts(), icodec.streams(video_stream_idx)
                        .time_base(), ovideo_st.time_base(), avutil.AV_ROUND_NEAR_INF));
                //数据大于2147483647可能会存在截断误差，不过已经足够
                packet.duration((int) avutil.av_rescale_q(packet.duration(), icodec.streams(video_stream_idx)
                        .time_base(), ovideo_st.time_base()));

                packet.stream_index(VIDEO_ID); //这里add_out_stream顺序有影响
                Log.e(TAG, "video\n");
            }//音频
            else if (packet.stream_index() == audio_stream_idx) {
                packet.pts(avutil.av_rescale_q_rnd(packet.pts(), icodec.streams(audio_stream_idx)
                        .time_base(), oaudio_st.time_base(), avutil.AV_ROUND_NEAR_INF));
                packet.dts(avutil.av_rescale_q_rnd(packet.dts(), icodec.streams(audio_stream_idx)
                        .time_base(), oaudio_st.time_base(), avutil.AV_ROUND_NEAR_INF));
                //数据大于2147483647可能会存在截断误差，不过已经足够
                packet.duration((int) avutil.av_rescale_q(packet.duration(), icodec.streams(audio_stream_idx)
                        .time_base(), oaudio_st.time_base()));

                packet.stream_index(AUDIO_ID); //这里add_out_stream顺序有影响
                Log.e(TAG, "audio\n");
            }

            current_segment_duration = (int) (segment_time - prev_segment_time + 0.5);
            actual_segment_durations[last_segment] = (current_segment_duration > 0 ? current_segment_duration : 1);

            if (segment_time - prev_segment_time >= SEGMENT_DURATION) {
                ret = avformat.av_write_trailer(ocodec);   // close ts file and free memory
                if (ret < 0) {
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
                    break;
                }
                ocodec.pb(pb);

                // Write a new header at the start of each file
                if (avformat.avformat_write_header(ocodec, (PointerPointer) null) != 0) {
                    Log.e(TAG, "Could not write mpegts header to first output file\n");
                    System.exit(1);
                }

                prev_segment_time = segment_time;
            }

            ret = avformat.av_interleaved_write_frame(ocodec, packet);
            if (ret < 0) {
                Log.e(TAG, "Warning: Could not write frame of stream\n");
            } else if (ret > 0) {
                Log.e(TAG, "End of stream requested\n");
                avcodec.av_free_packet(packet);
                break;
            }

            avcodec.av_free_packet(packet);
        } while (decode_done == 0);

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

        return;
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
            } catch (Exception e) {
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

    /**
     * 初始化混流 add by 波仔糕 on 2016/9/1
     */
    private void init_mux() {
        int ret = 0;
    /* allocate the output media context */
        avformat.avformat_alloc_output_context2(ocodec, null, "mp4", m_output_file_name.getString());
        if (ocodec == null) {
            return;
        }
        avformat.AVOutputFormat ofmt = null;
        ofmt = ocodec.oformat();

	  /*open the output file, if needed */
        try {
            if ((ofmt.flags() & avformat.AVFMT_NOFILE) == 0) {
                avformat.AVIOContext pb = new avformat.AVIOContext(null);
                if (avformat.avio_open(pb, m_output_file_name.getString(), avformat.AVIO_FLAG_WRITE) < 0) {
                    Log.e(TAG, "Could not open" + m_output_file_name.getString());
                    return;
                }
                ocodec.pb(pb);
            }
        } catch (Exception e) {
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

        switch (codec_type_t) {
            case AVMEDIA_TYPE_AUDIO:
                in_stream = icodec.streams(audio_stream_idx);
                break;
            case AVMEDIA_TYPE_VIDEO:
                in_stream = icodec.streams(video_stream_idx);
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

    /**
     * 释放混流 add by 波仔糕 on 2016/9/1
     */
    private int uinit_mux() {
        int i = 0;
        nRet = avformat.av_write_trailer(ocodec);
        if (nRet < 0) {
            Log.e(TAG, "Call av_write_trailer function failed\n");
        }
        if (vbsf_aac_adtstoasc != null) {
            avcodec.av_bitstream_filter_close(vbsf_aac_adtstoasc);
            vbsf_aac_adtstoasc = null;
        }

	/* Free the streams. */
        /*for (i = 0; i < ocodec.nb_streams(); i++) {
            avutil.av_freep(ocodec.streams(i).codec());
            avutil.av_freep(ocodec.streams(i));
        }*/
        if ((ocodec.oformat().flags() & avformat.AVFMT_NOFILE) == 0) {
        /* Close the output file. */
            avformat.avio_close(ocodec.pb());
        }
        avutil.av_free(ocodec);
        return 1;
    }

    /**
     * 释放分流 add by 波仔糕 on 2016/9/1
     */
    private int uinit_demux() {
    /* free the stream */
        avutil.av_free(icodec);
        if (vbsf_h264_toannexb != null) {
            avcodec.av_bitstream_filter_close(vbsf_h264_toannexb);
            vbsf_h264_toannexb = null;
        }
        return 1;
    }
}
