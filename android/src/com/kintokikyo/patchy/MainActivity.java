package com.kintokikyo.patchy;

import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.qtproject.qt.android.bindings.QtActivity;

public class MainActivity extends QtActivity {

    public boolean writeFileToUri(
        String localPath,
        String uriString) {

    android.util.Log.e(
            "PATCHY_MAIN",
            "JAVA WRITE HELPER START"
    );

    android.util.Log.e(
        "PATCHY_MAIN",
        "HELPER URI = " + uriString
    );
        
    int uriPermission =
        checkUriPermission(
                Uri.parse(uriString),
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        
    android.util.Log.e(
        "PATCHY_MAIN",
        "HELPER WRITE PERMISSION = " + uriPermission
    );
        
    for (android.content.UriPermission permission :
        getContentResolver().getPersistedUriPermissions()) {

    android.util.Log.e(
            "PATCHY_MAIN",
            "PERSISTED URI = "
            + permission.getUri()
            + " WRITE="
            + permission.isWritePermission()
    );
    }
        
    try (
        FileInputStream input =
                new FileInputStream(localPath);

        ParcelFileDescriptor pfd =
                getContentResolver().openFileDescriptor(
                        Uri.parse(uriString),
                        "w");

        FileOutputStream output =
                new FileOutputStream(
                        pfd.getFileDescriptor())
    ) {

        byte[] buffer = new byte[1024 * 1024];

        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }

        output.flush();

        android.util.Log.e(
                "PATCHY_MAIN",
                "JAVA WRITE HELPER = SUCCESS"
        );

        return true;

    } catch (Exception e) {

        android.util.Log.e(
                "PATCHY_MAIN",
                "JAVA WRITE HELPER = FAILED",
                e
        );

        return false;
    }
    }

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
                "onActivityResult requestCode="
                + requestCode
                + " resultCode="
                + resultCode
                + " data="
                + (data != null ? data.getData() : null));

        /*
         * ============================================================
         * CEK HASIL ANDROID URI GRANT
         * ============================================================
         */

        if (resultCode == RESULT_OK && data != null) {

            final Uri uri = data.getData();

            if (uri != null) {

                /*
                 * Tampilkan flag yang benar-benar dikembalikan
                 * oleh Android.
                 */
                final int returnedFlags =
                        data.getFlags();

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "RETURNED URI FLAGS = "
                        + returnedFlags);

                /*
                 * Ambil hanya READ + WRITE permission.
                 */
                final int takeFlags =
                        returnedFlags
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "TAKE URI FLAGS = "
                        + takeFlags);

                /*
                 * ====================================================
                 * COBA PERSIST URI PERMISSION
                 * ====================================================
                 */

                if (takeFlags != 0) {

                    try {

                        getContentResolver()
                                .takePersistableUriPermission(
                                        uri,
                                        takeFlags);

                        android.util.Log.e(
                                "PATCHY_MAIN",
                                "TAKE PERSISTABLE PERMISSION = SUCCESS");

                    } catch (SecurityException e) {

                        android.util.Log.e(
                                "PATCHY_MAIN",
                                "TAKE PERSISTABLE PERMISSION = FAILED: "
                                + e.toString());

                    } catch (Exception e) {

                        android.util.Log.e(
                                "PATCHY_MAIN",
                                "TAKE PERSISTABLE PERMISSION = ERROR: "
                                + e.toString());
                    }

                } else {

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "TAKE PERSISTABLE PERMISSION = SKIPPED, "
                            + "NO READ/WRITE FLAG");
                }

                /*
                 * ====================================================
                 * TES UTAMA
                 *
                 * Coba melakukan operasi yang sama seperti
                 * write_file_to_android_uri() di C++:
                 *
                 * ContentResolver
                 *      ↓
                 * openFileDescriptor(uri, "w")
                 *
                 * Kalau ini berhasil, berarti Android permission
                 * sebenarnya sudah benar dan kita perlu fokus ke C++.
                 * Kalau ini gagal, masalahnya memang terjadi di
                 * Android URI permission/provider.
                 * ====================================================
                 */

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "JAVA WRITE TEST START");

                ParcelFileDescriptor testDescriptor = null;

                try {

                    testDescriptor =
                            getContentResolver()
                            .openFileDescriptor(
                                    uri,
                                    "w");

                    if (testDescriptor != null) {

                        android.util.Log.e(
                                "PATCHY_MAIN",
                                "JAVA WRITE TEST = SUCCESS");

                        testDescriptor.close();

                        testDescriptor = null;

                    } else {

                        android.util.Log.e(
                                "PATCHY_MAIN",
                                "JAVA WRITE TEST = FAILED: "
                                + "openFileDescriptor returned null");
                    }

                } catch (SecurityException e) {

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "JAVA WRITE TEST = SECURITY EXCEPTION: "
                            + e.toString());

                } catch (java.io.FileNotFoundException e) {

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "JAVA WRITE TEST = FILE NOT FOUND: "
                            + e.toString());

                } catch (Exception e) {

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "JAVA WRITE TEST = ERROR: "
                            + e.toString());

                } finally {

                    if (testDescriptor != null) {

                        try {
                            testDescriptor.close();
                        } catch (Exception ignored) {
                        }
                    }
                }

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "JAVA WRITE TEST END");
            }
        }

        /*
         * Setelah semua tes Java selesai,
         * baru serahkan hasil Activity ke Qt.
         */
        super.onActivityResult(
                requestCode,
                resultCode,
                data);
    }
}
