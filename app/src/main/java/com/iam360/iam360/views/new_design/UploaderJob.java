package com.iam360.iam360.views.new_design;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.design.widget.Snackbar;
import android.util.Log;
import android.view.View;

import com.iam360.iam360.bus.BusProvider;
import com.iam360.iam360.bus.RecordFinishedEvent;
import com.iam360.iam360.bus.RecordFinishedPreviewEvent;
import com.iam360.iam360.model.LogInReturn;
import com.iam360.iam360.model.Optograph;
import com.iam360.iam360.network.ApiConsumer;
import com.iam360.iam360.record.Alignment;
import com.iam360.iam360.record.GlobalState;
import com.iam360.iam360.record.Recorder;
import com.iam360.iam360.record.Stitcher;
import com.iam360.iam360.util.Cache;
import com.iam360.iam360.util.CameraUtils;
import com.iam360.iam360.util.DBHelper;
import com.path.android.jobqueue.Job;
import com.path.android.jobqueue.Params;
import com.path.android.jobqueue.RetryConstraint;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.MultipartBuilder;
import com.squareup.okhttp.RequestBody;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;
import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-02-12
 */
public class UploaderJob extends Job {

    private UUID id;
    private DBHelper mydb;
    private Cache cache;
    private ApiConsumer apiConsumer;
    private Optograph optograph;

    protected UploaderJob(UUID uuid) {
        super(new Params(1));
        this.id = uuid;
        this.cache = Cache.open();

        String userToken = cache.getString(Cache.USER_TOKEN);
        apiConsumer = new ApiConsumer(userToken.equals("")? null:userToken);
        optograph = new Optograph(uuid.toString());

    }

    @Override
    public void onAdded() {
        Timber.v("UploaderJob added to disk");
    }

    @Override
    public void onRun() throws Throwable {
        Timber.d("UPLOADER JOB");
        mydb = new DBHelper(getApplicationContext());

        // upload images only if tagged for upload
        if(checkIfForUpload()) getLocalImage(optograph);

    }

    /**
     *
     * @return true if for upload, false if for postlater
     */
    private boolean checkIfForUpload() {
        Cursor res = mydb.getData(id.toString(), DBHelper.OPTO_TABLE_NAME, DBHelper.OPTOGRAPH_ID);

        res.moveToFirst();
        if (res.getCount() == 0) return false;

        if(res.getInt(res.getColumnIndex(DBHelper.OPTOGRAPH_SHOULD_BE_PUBLISHED)) == 0) return false;
        else return true;

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
    }


    private void getLocalImage(Optograph opto) {
        cache.save(Cache.UPLOAD_ON_GOING, true);
        Log.d("myTag", "Path: " + CameraUtils.PERSISTENT_STORAGE_PATH + opto.getId());
        File dir = new File(CameraUtils.PERSISTENT_STORAGE_PATH + opto.getId());

        List<String> filePathList = new ArrayList<>();

        if (dir.exists()) {// remove the not notation here
            File[] files = dir.listFiles();
            for (int i = 0; i < files.length; ++i) {
                File file = files[i];
                if (file.isDirectory() && !file.getName().contains("preview")) {
                    Log.d("myTag", "getName: " + file.getName() + " getPath: " + file.getPath());
                    for (String s : file.list()) {
                        filePathList.add(file.getPath() + "/" + s);
                    }
                } else {
                    // ignore
                }
            }
        }
        Log.d("myTag", "before: ");
        int ctr = 0;
        for (boolean i : opto.getLeftFace().getStatus()) {
            Log.d("myTag", "left " + ctr + ": " + i);
            ctr += 1;
        }
        int ctr2 = 0;
        for (boolean i : opto.getRightFace().getStatus()) {
            Log.d("myTag", "right " + ctr2 + ": " + i);
            ctr2 += 1;
        }

        new UploadCubeImages().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, filePathList);
    }

    // try using AbstractQueuedSynchronizer
    class UploadCubeImages extends AsyncTask<List<String>, Void, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(List<String>... params) {
            for (List<String> sL : params) {
                for (String s : sL) {
                    String[] s3 = s.split("/");
                    Log.d("myTag", "onNext s: " + s + " s3 length: " + s3.length + " (s2[s2.length - 1]): " + (s3[s3.length - 1]));
                    Log.d("myTag", " split: " + (s3[s3.length - 1].split("\\."))[0]);
                    int side = Integer.valueOf((s3[s3.length - 1].split("\\."))[0]);
                    String face = s.contains("right") ? "r" : "l";
                    Log.d("myTag", " face: " + face);

                    uploadFaceImage(optograph, s, face, side);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            Log.d("myTag", "onPostExecute");

            Cursor res = mydb.getData(id.toString(), DBHelper.FACES_TABLE_NAME, DBHelper.FACES_ID);
            res.moveToFirst();
            if (res.getCount() == 0) return;
            String stringRes = "" + DBHelper.FACES_LEFT_ZERO + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_ZERO)) +
                    "\n" + DBHelper.FACES_LEFT_ONE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_ONE)) +
                    "\n" + DBHelper.FACES_LEFT_TWO + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_TWO)) +
                    "\n" + DBHelper.FACES_LEFT_THREE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_THREE)) +
                    "\n" + DBHelper.FACES_LEFT_FOUR + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_FOUR)) +
                    "\n" + DBHelper.FACES_LEFT_FIVE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_LEFT_FIVE)) +
                    "\n" + DBHelper.FACES_RIGHT_ZERO + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_ZERO)) +
                    "\n" + DBHelper.FACES_RIGHT_ONE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_ONE)) +
                    "\n" + DBHelper.FACES_RIGHT_TWO + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_TWO)) +
                    "\n" + DBHelper.FACES_RIGHT_THREE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_THREE)) +
                    "\n" + DBHelper.FACES_RIGHT_FOUR + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_FOUR)) +
                    "\n" + DBHelper.FACES_RIGHT_FIVE + " " + res.getString(res.getColumnIndex(DBHelper.FACES_RIGHT_FIVE));
            Log.d("myTag", "" + stringRes);
            Log.d("myTag", "checkIfAllImagesUploaded " + mydb.checkIfAllImagesUploaded(id.toString()));
            cache.save(Cache.UPLOAD_ON_GOING, false);
            if (mydb.checkIfAllImagesUploaded(id.toString())) {
                mydb.updateColumnOptograph(id.toString(), DBHelper.OPTOGRAPH_IS_ON_SERVER, 1);
            } else {
                mydb.updateColumnOptograph(id.toString(), DBHelper.OPTOGRAPH_SHOULD_BE_PUBLISHED, 0);
            }
        }
    }



    private void uploadFaceImage(Optograph opto, String filePath, String face, int side) {
        String[] s2 = filePath.split("/");
        String fileName = s2[s2.length - 1];

        if (face.equals("l") && opto.getLeftFace().getStatus()[side]) {
            Log.d("myTag"," already uploaded: "+face+side);
            return;
        }
        else if (opto.getRightFace().getStatus()[side]) {
            Log.d("myTag"," already uploaded: "+face+side);
            return;
        }

        Bitmap bm = null;

        try {
            bm = BitmapFactory.decodeFile(filePath);
        } catch (Exception e) {
            Log.e(e.getClass().getName(), e.getMessage());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 75, bos);
        byte[] data = bos.toByteArray();

        RequestBody fbody = RequestBody.create(MediaType.parse("image/jpeg"), data);
        RequestBody fbodyMain = new MultipartBuilder()
                .type(MultipartBuilder.FORM)
                .addFormDataPart("asset", face + fileName, fbody)
                .addFormDataPart("key", face + side)
                .build();
        Log.d("myTag", "asset: " + face + fileName + " key: " + face + fileName.replace(".jpg", ""));
        apiConsumer.uploadOptoImage(opto.getId(), fbodyMain, (OptoImagePreviewActivity.optoType360), new Callback<LogInReturn.EmptyResponse>() {
            @Override
            public void onResponse(Response<LogInReturn.EmptyResponse> response, Retrofit retrofit) {
                Log.d("myTag", "onResponse uploadImage isSuccess? " + response.isSuccess());
                Log.d("myTag", "onResponse message: " + response.message());
                Log.d("myTag", "onResponse body: " + response.body());
                Log.d("myTag", "onResponse raw: " + response.raw());
                if (face.equals("l"))
                    opto.getLeftFace().setStatusByIndex(side, response.isSuccess());
                else opto.getRightFace().setStatusByIndex(side, response.isSuccess());
                updateFace(opto, face, side, response.isSuccess() ? 1 : 0);

                Log.d("myTag", "after: ");
                int ctr = 0;
                for (boolean i : opto.getLeftFace().getStatus()) {
                    Log.d("myTag", "left " + ctr + ": " + i);
                    ctr += 1;
                }
                int ctr2 = 0;
                for (boolean i : opto.getRightFace().getStatus()) {
                    Log.d("myTag", "right " + ctr2 + ": " + i);
                    ctr2 += 1;
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.d("myTag", "onFailure uploadImage: " + t.getMessage());
                if (face.equals("l")) opto.getLeftFace().setStatusByIndex(side, false);
                else opto.getRightFace().setStatusByIndex(side, false);
            }
        });
//        while (flag == 2) {
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                Thread.interrupted();
//            }
//        }
    }

    private void updateFace(Optograph opto, String face, int side, int value) {
        Log.d("myTag", "updateFace");

        String column = "faces_";
        if (face.equals("l")) column += "left_";
        else column += "right_";

        if (side == 0) column += "zero";
        else if (side == 1) column += "one";
        else if (side == 2) column += "two";
        else if (side == 3) column += "three";
        else if (side == 4) column += "four";
        else if (side == 5) column += "five";

        mydb.updateFace(opto.getId(), column, value);
    }

}
