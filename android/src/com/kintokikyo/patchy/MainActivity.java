package com.kintokikyo.patchy;

import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.database.Cursor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.qtproject.qt.android.bindings.QtActivity;

public class MainActivity extends QtActivity {

    private static final int PATCHY_DIRECTORY_REQUEST = 0x5047;

    private volatile boolean patchyDirectoryResultReady = false;
    private volatile int patchyDirectoryResultCode = RESULT_CANCELED;
    private volatile String patchyDirectoryResultUri = null;

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
    
    public void pickPatchyDirectory(int requestCode) {

        patchyDirectoryResultReady = false;
        patchyDirectoryResultCode = RESULT_CANCELED;
        patchyDirectoryResultUri = null;

        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        android.util.Log.e(
                "PATCHY_MAIN",
                "OPEN DIRECTORY PICKER");

        startActivityForResult(
                intent,
                requestCode);
    }
    
    public String consumePatchyDirectoryResult(int requestCode) {

        if (!patchyDirectoryResultReady) {
            return null;
        }

        if (requestCode != PATCHY_DIRECTORY_REQUEST) {
            return null;
        }

        patchyDirectoryResultReady = false;

        if (patchyDirectoryResultCode != RESULT_OK) {
            return "";
        }

        return patchyDirectoryResultUri != null
                ? patchyDirectoryResultUri
                : "";
    }
    
    public boolean writeFileToPatchyDirectory(
            String localPath,
            String directoryUriString,
            String fileName,
            String mimeType) {

        android.util.Log.e(
                "PATCHY_MAIN",
                "SEQUENCE WRITE START: " + fileName);

        try {

            Uri treeUri =
                    Uri.parse(directoryUriString);

            /*
             * Ambil document ID dari tree URI.
             */
            String treeDocumentId =
                    DocumentsContract.getTreeDocumentId(treeUri);

            /*
             * Ubah tree URI menjadi URI document folder.
             */
            Uri parentDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            treeDocumentId);

            /*
             * Cari apakah file dengan nama yang sama
             * sudah ada di folder tersebut.
             */
            Uri childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                            treeUri,
                            treeDocumentId);

            Cursor cursor = null;

            try {

                cursor =
                        getContentResolver().query(
                                childrenUri,
                                new String[] {
                                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                },
                                null,
                                null,
                                null);

                if (cursor != null) {

                    int idIndex =
                            cursor.getColumnIndex(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);

                    int nameIndex =
                            cursor.getColumnIndex(
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);

                    while (cursor.moveToNext()) {

                        String existingId =
                                cursor.getString(idIndex);

                        String existingName =
                                cursor.getString(nameIndex);

                        if (fileName.equals(existingName)) {

                            Uri existingUri =
                                    DocumentsContract.buildDocumentUriUsingTree(
                                            treeUri,
                                            existingId);

                            android.util.Log.e(
                                    "PATCHY_MAIN",
                                    "DELETING EXISTING: "
                                    + existingName);

                            try {

                                DocumentsContract.deleteDocument(
                                        getContentResolver(),
                                        existingUri);

                            } catch (Exception e) {

                                android.util.Log.e(
                                        "PATCHY_MAIN",
                                        "DELETE EXISTING FAILED",
                                        e);
                            }

                            break;
                        }
                    }
                }

            } finally {

                if (cursor != null) {
                    cursor.close();
                }
            }

            /*
             * Buat file baru di folder tujuan.
             */
            Uri childUri =
                    DocumentsContract.createDocument(
                            getContentResolver(),
                            parentDocumentUri,
                            mimeType,
                            fileName);

            if (childUri == null) {

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "CREATE DOCUMENT FAILED: "
                        + fileName);

                return false;
            }

            android.util.Log.e(
                    "PATCHY_MAIN",
                    "DOCUMENT CREATED: "
                    + childUri);

            /*
             * Buka temporary file + destination Android file.
             */
            try (
                    FileInputStream input =
                            new FileInputStream(localPath);

                    ParcelFileDescriptor pfd =
                            getContentResolver().openFileDescriptor(
                                    childUri,
                                    "w");

                    FileOutputStream output =
                            new FileOutputStream(
                                    pfd.getFileDescriptor())
            ) {

                byte[] buffer =
                        new byte[1024 * 1024];

                int bytesRead;

                while ((bytesRead =
                        input.read(buffer)) != -1) {

                    output.write(
                            buffer,
                            0,
                            bytesRead);
                }

                output.flush();

                android.util.Log.e(
                        "PATCHY_MAIN",
                        "SEQUENCE WRITE SUCCESS: "
                        + fileName);

                return true;
            }

        } catch (Exception e) {

            android.util.Log.e(
                    "PATCHY_MAIN",
                    "SEQUENCE WRITE FAILED: "
                    + fileName,
                    e);

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
         * PATCHY IMAGE SEQUENCE - FOLDER RESULT
         * ============================================================
         */

        if (requestCode == PATCHY_DIRECTORY_REQUEST) {

            patchyDirectoryResultCode = resultCode;

            if (resultCode == RESULT_OK && data != null) {

                final Uri uri = data.getData();

                if (uri != null) {

                    patchyDirectoryResultUri =
                            uri.toString();

                    final int returnedFlags =
                            data.getFlags();

                    final int takeFlags =
                            returnedFlags
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "DIRECTORY URI = "
                            + patchyDirectoryResultUri);

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "DIRECTORY RETURNED FLAGS = "
                            + returnedFlags);

                    android.util.Log.e(
                            "PATCHY_MAIN",
                            "DIRECTORY TAKE FLAGS = "
                            + takeFlags);

                    if (takeFlags != 0) {

                        try {

                            getContentResolver()
                                    .takePersistableUriPermission(
                                            uri,
                                            takeFlags);

                            android.util.Log.e(
                                    "PATCHY_MAIN",
                                    "DIRECTORY PERSIST PERMISSION = SUCCESS");

                        } catch (SecurityException e) {

                            android.util.Log.e(
                                    "PATCHY_MAIN",
                                    "DIRECTORY PERSIST PERMISSION = FAILED: "
                                    + e.toString());

                        } catch (Exception e) {

                            android.util.Log.e(
                                    "PATCHY_MAIN",
                                    "DIRECTORY PERSIST PERMISSION = ERROR: "
                                    + e.toString());
                        }
                    }
                }
            }

            patchyDirectoryResultReady = true;

            android.util.Log.e(
                    "PATCHY_MAIN",
                    "PATCHY DIRECTORY RESULT READY");

            /*
             * Jangan biarkan blok Save di bawah memperlakukan
             * folder URI sebagai file.
             */
            super.onActivityResult(
                    requestCode,
                    resultCode,
                    data);

            return;
        }

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
