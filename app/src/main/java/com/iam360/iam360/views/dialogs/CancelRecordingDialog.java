package com.iam360.iam360.views.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;

import com.iam360.iam360.R;
import com.iam360.iam360.views.OverlayNavigationFragment;

/**
 * @author Nilan Marktanner
 * @date 2016-02-12
 */
public class CancelRecordingDialog extends DialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // Use the Builder class for convenient dialog construction
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(R.string.dialog_cancel_recording)
                .setPositiveButton(getResources().getString(R.string.dialog_cancel), (dialog, which) -> {
                    if (getTargetFragment() instanceof OverlayNavigationFragment) {
                        ((OverlayNavigationFragment) getTargetFragment()).cancel();
                    }
                }).setNegativeButton(getResources().getString(R.string.dialog_dont_cancel), (dialog, which) -> {
                    dismiss();
                });
        // Create the AlertDialog object and return it
        return builder.create();
    }
}