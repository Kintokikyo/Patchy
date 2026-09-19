package com.kintokikyo.patchy;

import android.content.Intent;
import android.net.Uri;

import org.qtproject.qt.android.bindings.QtActivity;

public class MainActivity extends QtActivity {

    @Override
    public void startActivityForResult(
            Intent intent,
            int requestCode) {

        if (Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) {
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        final Uri uri = data.getData();

        if (uri == null) {
            return;
        }

        final int takeFlags =
                data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (takeFlags == 0) {
            return;
        }

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
