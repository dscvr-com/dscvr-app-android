package com.iam360.dscvr.views.activity;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.iam360.dscvr.R;
import com.iam360.dscvr.bus.BusProvider;
import com.iam360.dscvr.bus.RecordFinishedEvent;
import com.iam360.dscvr.record.GlobalState;
import com.squareup.otto.Subscribe;

import timber.log.Timber;

public class LoadingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);
    }

    @Subscribe
    public void receiveFinishedImage(RecordFinishedEvent recordFinishedEvent) {
        Timber.d("receiveFinishedImage");
        if(!recordFinishedEvent.wasSuccesful()){
            Toast.makeText(this,getString(R.string.error), Toast.LENGTH_SHORT).show();
        }
        finishedRecievingImage();
    }

    public void finishedRecievingImage() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        if(!GlobalState.isAnyJobRunning){
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            super.onResume();
        }
        BusProvider.getInstance().register(this);
        super.onResume();
    }

    @Override
    protected void onPause() {
        BusProvider.getInstance().unregister(this);
        super.onPause();
    }
}
