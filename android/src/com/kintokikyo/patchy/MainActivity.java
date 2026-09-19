package com.kintokikyo.patchy;

import android.content.Intent;
import android.net.Uri;

import org.qtproject.qt.android.bindings.QtActivity;

public class MainActivity extends QtActivity {

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        android.util.Log.e(
                "PATCHY_MAIN",
                "CUSTOM MAIN ACTIVITY onCreate()");

        super.onCreate(savedInstanceState);
    }

    @Override
    public void startActivityForResult(
            Intent intent,
            int requestCode) {

        android.util.Log.e(
                "PATCHY_MAIN",
                "CUSTOM startActivityForResult CALLED: "
                + intent.getAction());

        if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) {

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

            android.util.Log.e(
                    "PATCHY_MAIN",
                    "CREATE_DOCUMENT flags = "
                    + intent.getFlags());
        }

        super.startActivityForResult(
                intent,
                requestCode);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        android.util.Log.e(
                "PATCHY_MAIN",
                "onActivityResult requestCode=" + requestCode
                + " resultCode=" + resultCode
                + " data=" + (data != null ? data.getData() : null));

        /*
         * Ambil persistable URI permission SEBELUM
         * hasil diberikan ke Qt melalui super.onActivityResult().
         */
        if (resultCode == RESULT_OK && data != null) {

            final Uri uri = data.getData();

            if (uri != null) {

                final int takeFlags =
                        data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                if (takeFlags != 0) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                takeFlags);
                    } catch (SecurityException ignored) {
                        // Some providers do not offer persistable permissions.
                        // The temporary URI grant can still remain valid.
                    }
                }
            }
        }

        /*
         * Qt tetap harus menerima hasil Activity.
         */
        super.onActivityResult(
                requestCode,
                resultCode,
                data);
    }
}
