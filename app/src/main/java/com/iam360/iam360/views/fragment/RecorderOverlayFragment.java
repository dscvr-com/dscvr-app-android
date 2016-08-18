package com.iam360.iam360.views.fragment;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import butterknife.Bind;
import butterknife.ButterKnife;
import com.iam360.iam360.R;
import com.iam360.iam360.record.GlobalState;
import com.iam360.iam360.util.Constants;
import com.iam360.iam360.views.activity.RecorderActivity;

public class RecorderOverlayFragment extends Fragment {
    public static final int PREVIEW_RECORD = 1;
    public static final int RECORDING = 2;

    private int RECORDING_MODE = Constants.MODE_CENTER;
    private int currentMode;
    private int screenWidth = Constants.getInstance().getDisplayMetrics().widthPixels;
    private int MODE = PREVIEW_RECORD;

    private int PICK_IMAGE_REQUEST = 1;

    @Bind(R.id.record_button) ImageButton recordButton;
    @Bind(R.id.record_progress) ProgressBar recordProgress;

    @Bind(R.id.camera_overlay) FrameLayout cameraOverlay;
    @Bind(R.id.instruction) TextView instruction;
    @Bind(R.id.crosshair) View crosshair;
    @Bind(R.id.arrow) View arrow;
    @Bind(R.id.line) View line;
    @Bind(R.id.angle) View angle;
    @Bind(R.id.progress_point) View progressPoint;
    @Bind(R.id.arc) View arc;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_recorder_overlay, container, false);
        ButterKnife.bind(this, view);

        initializeNavigationButtons(view);

        return view;
    }

    private void initializeNavigationButtons(View view) {

        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                MODE = RECORDING_MODE;
                recordButton.setVisibility(View.INVISIBLE);
                cameraOverlay.setVisibility(View.VISIBLE);
                instruction.setText(getActivity().getResources().getText(R.string.record_instruction_follow));

                ((RecorderActivity) getActivity()).startRecording();

            }
        });

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        getView().setFocusableInTouchMode(true);
        getView().requestFocus();

        getView().setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (currentMode == RECORDING || currentMode == PREVIEW_RECORD) {
//                        return cancelGroup.callOnClick();
                    }
                }
            }
            return false;
        });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        ButterKnife.unbind(this);
    }

    public void setAngleRotation(float rotation) {

        line.setRotation((float)Math.toDegrees(rotation));
    }

    public void setArrowRotation(float rotation) {
        arrow.setRotation((float)Math.toDegrees(rotation));
    }

    public void setArrowVisible(boolean visible) {
        if (visible) {
            arrow.setVisibility(View.VISIBLE);
            crosshair.setBackground(getActivity().getResources().getDrawable(R.drawable.crosshair));
        } else {
            arrow.setVisibility(View.INVISIBLE);
            crosshair.setBackground(getActivity().getResources().getDrawable(R.drawable.crosshair_red));
        }
    }

    public void setGuideLinesVisible(boolean visible) {
        if(visible) {
            line.setVisibility(View.VISIBLE);
            angle.setVisibility(View.VISIBLE);
        } else {
            line.setVisibility(View.INVISIBLE);
            angle.setVisibility(View.INVISIBLE);
        }
    }

    public void setProgress(float progress) {
        progressPoint.setX((screenWidth - 100) * progress + 50);
    }

    public boolean isPreviewMode() {
        if(MODE == PREVIEW_RECORD) return true;
        else return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (GlobalState.isAnyJobRunning) {
            recordProgress.setVisibility(View.VISIBLE);
        }
    }

}