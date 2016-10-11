package com.github.crazyorr.ffmpegrecorder.function;

import android.content.Context;

import com.github.crazyorr.ffmpegrecorder.FFmpegRecordActivity;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module(
        injects = FFmpegRecordActivity.class
)
@SuppressWarnings("unused")
public class DaggerDependencyModule {

    private final Context context;

    public DaggerDependencyModule(Context context) {
        this.context = context;
    }

    @Provides @Singleton
    FFmpeg provideFFmpeg() {
        return FFmpeg.getInstance(context.getApplicationContext());
    }

}
