package com.iam360.iam360.views.record;

import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.media.Image;
import android.media.MediaMetadata;
import android.util.Log;

import com.iam360.iam360.DscvrApp;
import com.iam360.iam360.record.Alignment;
import com.iam360.iam360.views.UploaderJob;
import com.path.android.jobqueue.Job;
import com.path.android.jobqueue.Params;
import com.path.android.jobqueue.RetryConstraint;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import com.iam360.iam360.bus.BusProvider;
import com.iam360.iam360.bus.RecordFinishedEvent;
import com.iam360.iam360.bus.RecordFinishedPreviewEvent;
import com.iam360.iam360.record.GlobalState;
import com.iam360.iam360.record.Recorder;
import com.iam360.iam360.record.Stitcher;
import com.iam360.iam360.util.CameraUtils;
import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-02-12
 */
public class FinishRecorderJob extends Job {

    private UUID id;
    public FinishRecorderJob(UUID uuid) {
        // TODO: persist job?
        super(new Params(1));
        this.id = uuid;
    }

    @Override
    public void onAdded() {
        Timber.v("FinishRecorderJob added to disk");
    }

    @Override
    public void onRun() throws Throwable {

        Timber.v("finishing Recorder...");
        Recorder.finish();
        Timber.v("Sending event");

//TODO        http://stackoverflow.com/questions/15431768/how-to-send-event-from-service-to-activity-with-otto-event-bus

        Bitmap previewBitmap = null;
        if(Recorder.previewAvailable()) {
            previewBitmap = Recorder.getPreviewImage();
            BusProvider.getInstance().post(new RecordFinishedPreviewEvent(previewBitmap));
            Timber.v("post of placeholder");
        }

        if(previewBitmap != null)
            CameraUtils.saveBitmapToLocation(previewBitmap, CameraUtils.PERSISTENT_STORAGE_PATH + id + "/preview/placeholder.jpg");
        Timber.v("after save of placeholder");

        Timber.v("disposing Recorder...");
        Recorder.disposeRecorder();
        Timber.v("Stitcher is getting result...");

        Alignment.align(CameraUtils.CACHE_PATH + "post/", CameraUtils.CACHE_PATH + "shared/", CameraUtils.CACHE_PATH);

        Bitmap[] bitmaps = Stitcher.getResult(CameraUtils.CACHE_PATH + "left/", CameraUtils.CACHE_PATH + "shared/");
//        UUID id = UUID.randomUUID();
        for (int i = 0; i < bitmaps.length; ++i) {
            CameraUtils.saveBitmapToLocation(bitmaps[i], CameraUtils.PERSISTENT_STORAGE_PATH + id + "/left/" + i + ".jpg");
            bitmaps[i].recycle();
        }

//        Bitmap eqmap = Stitcher.getEQResult(CameraUtils.CACHE_PATH + "left/", CameraUtils.CACHE_PATH + "shared/");
//        CameraUtils.saveBitmapToLocation(eqmap, CameraUtils.PERSISTENT_STORAGE_PATH + id + ".jpg");

        bitmaps = Stitcher.getResult(CameraUtils.CACHE_PATH + "right/", CameraUtils.CACHE_PATH + "shared/");
        for (int i = 0; i < bitmaps.length; ++i) {
            CameraUtils.saveBitmapToLocation(bitmaps[i], CameraUtils.PERSISTENT_STORAGE_PATH + id + "/right/" + i + ".jpg");
            bitmaps[i].recycle();
        }

//        ExifInterface exif = null;
//        try {
//            String filepath = CameraUtils.PERSISTENT_STORAGE_PATH + id + ".jpg";
//            File file = new File(filepath);
//            Log.e("myTag"," inside try");
//            exif = new ExifInterface(file.getCanonicalPath());
//            Log.e("myTag", " after declaration model: " + exif.getAttribute(ExifInterface.TAG_MODEL) + " make: " + exif.getAttribute(ExifInterface.TAG_MAKE));
//            exif.setAttribute(ExifInterface.TAG_MODEL, "RICOH THETA S");
//            exif.setAttribute(ExifInterface.TAG_MAKE, "RICOH");
//            Log.e("myTag", " after setting of attributes");
//            exif.saveAttributes();
//            Log.e("myTag"," success saving of attributes model: "+exif.getAttribute(ExifInterface.TAG_MODEL)+" make: "+exif.getAttribute(ExifInterface.TAG_MAKE));
//        } catch (IOException e) {
//            Log.e("myTag"," ERROR adding of attributes message: "+e.getMessage());
//        }

        Timber.v("FinishRecorderJob finished");
        Stitcher.clear(CameraUtils.CACHE_PATH + "preview/", CameraUtils.CACHE_PATH + "shared/");
        Stitcher.clear(CameraUtils.CACHE_PATH + "left/", CameraUtils.CACHE_PATH + "shared/");
        Stitcher.clear(CameraUtils.CACHE_PATH + "right/", CameraUtils.CACHE_PATH + "shared/");

        // TODO: fire event or otherwise handle refresh
        BusProvider.getInstance().post(new RecordFinishedEvent());
        GlobalState.isAnyJobRunning = false;
        GlobalState.shouldHardRefreshFeed = true;
        Timber.v("finish all job");

        DscvrApp.getInstance().getJobManager().addJobInBackground(new UploaderJob(id));

    }

    @Override
    protected RetryConstraint shouldReRunOnThrowable(Throwable throwable, int runCount,
                                                     int maxRunCount) {
        // An error occurred in onRun.
        // Return value determines whether this job should retry or cancel. You can further
        // specifcy a backoff strategy or change the job's priority. You can also apply the
        // delay to the whole group to preserve jobs' running order.
        return RetryConstraint.CANCEL;
    }

    @Override
    protected void onCancel() {
        GlobalState.isAnyJobRunning = false;
        Timber.e("FinishRecorderJob has been canceled");
    }
}
