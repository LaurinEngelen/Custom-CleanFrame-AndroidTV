package com.example.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Window;

public class InputPickerActivity extends Activity {

    private static final String[] LABELS = new String[]{
            "HDMI 1 (PlayStation 5)",
            "HDMI 2",
            "HDMI 3 (ARC)",
            "AV / Composite",
            "Live TV / Antenne"
    };

    private static final String[] IDS = new String[]{
            TvInputHelper.INPUT_HDMI1,
            TvInputHelper.INPUT_HDMI2,
            TvInputHelper.INPUT_HDMI3,
            TvInputHelper.INPUT_AV,
            TvInputHelper.INPUT_TUNER
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("TV-Eingang wählen (Input)")
                .setItems(LABELS, (d, which) -> {
                    if (which >= 0 && which < IDS.length) {
                        TvInputHelper.switchToInput(InputPickerActivity.this, IDS[which], LABELS[which]);
                    }
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .setOnDismissListener(d -> finish())
                .create();

        dialog.show();
        if (dialog.getListView() != null) {
            dialog.getListView().requestFocus();
            dialog.getListView().setSelection(0);
        }
    }

    @Override
    protected void onNewIntent( android.content.Intent intent) {
        super.onNewIntent(intent);
        finish();
    }
}
