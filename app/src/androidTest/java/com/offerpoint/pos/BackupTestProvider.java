package com.offerpoint.pos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Installed only in the test APK; Java avoids depending on target-app Kotlin in its provider process. */
public final class BackupTestProvider extends ContentProvider {
    private final ConcurrentHashMap<String, Boolean> writes = new ConcurrentHashMap<>();
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-zA-Z0-9-]+")) throw new FileNotFoundException("Invalid test file");
        boolean writing = mode.contains("w");
        if (writing && "1".equals(uri.getQueryParameter("failWrite"))) throw new FileNotFoundException("Test provider refused write");
        if (writing) {
            writes.put(name, true);
            if ("1".equals(uri.getQueryParameter("delayWrite"))) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Interrupted"); }
            }
        }
        File file = new File(getContext().getCacheDir(), "backup-test-" + name);
        if (!writing && "1".equals(uri.getQueryParameter("badRead"))) {
            file = new File(getContext().getCacheDir(), "empty-read");
            try (FileOutputStream output = new FileOutputStream(file)) { output.flush(); }
            catch (IOException e) { throw new FileNotFoundException("Cannot create test file"); }
        }
        return ParcelFileDescriptor.open(file, writing ? ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) { Bundle result = new Bundle(); result.putBoolean("written", Boolean.TRUE.equals(writes.get(arg))); return result; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
