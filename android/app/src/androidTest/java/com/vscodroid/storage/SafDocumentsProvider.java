package com.vscodroid.storage;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A real provider whose documents exist only in the instrumentation APK's storage. */
public final class SafDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.vscodroid.test.saf.documents";
    public static final String ROOT_ID = "root";
    public static final String FILE_NAME = "notes.txt";
    public static final String DIRECTORY_ID = "folder";
    public static final String KNOWN_CHILD_ID = "folder/known.txt";
    public static final String NEW_CHILD_ID = "folder/new.txt";

    private final AtomicInteger writeOpens = new AtomicInteger();
    private final AtomicInteger createCalls = new AtomicInteger();
    private volatile CountDownLatch readEntered = new CountDownLatch(1);
    private volatile CountDownLatch readRelease = new CountDownLatch(0);
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private volatile boolean failDirectoryListing;

    @Override
    public boolean onCreate() {
        return true;
    }

    private File document() {
        return new File(new File(getContext().getFilesDir(), "saf-documents"), FILE_NAME);
    }

    private File directoryDocument(String id) throws FileNotFoundException {
        if (!DIRECTORY_ID.equals(id) && !KNOWN_CHILD_ID.equals(id) && !NEW_CHILD_ID.equals(id)) {
            throw new FileNotFoundException(id);
        }
        return new File(document().getParentFile(), id);
    }

    private void clearDirectory() throws IOException {
        Files.deleteIfExists(directoryDocument(KNOWN_CHILD_ID).toPath());
        Files.deleteIfExists(directoryDocument(NEW_CHILD_ID).toPath());
        Files.deleteIfExists(directoryDocument(DIRECTORY_ID).toPath());
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES
        });
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(Root.COLUMN_TITLE, "VSCodroid test documents")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE)
            .add(Root.COLUMN_MIME_TYPES, "*/*");
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = documents(projection);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = documents(projection);
        if (ROOT_ID.equals(parentDocumentId)) {
            if (document().isFile()) addDocument(cursor, FILE_NAME);
            if (directoryDocument(DIRECTORY_ID).isDirectory()) addDocument(cursor, DIRECTORY_ID);
        } else if (DIRECTORY_ID.equals(parentDocumentId)) {
            if (failDirectoryListing) throw new FileNotFoundException("Directory listing unavailable");
            if (!directoryDocument(DIRECTORY_ID).isDirectory()) throw new FileNotFoundException(parentDocumentId);
            if (directoryDocument(KNOWN_CHILD_ID).isFile()) addDocument(cursor, KNOWN_CHILD_ID);
            if (directoryDocument(NEW_CHILD_ID).isFile()) addDocument(cursor, NEW_CHILD_ID);
        } else {
            throw new FileNotFoundException(parentDocumentId);
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT_ID.equals(parentDocumentId) && (FILE_NAME.equals(documentId)
            || DIRECTORY_ID.equals(documentId) || KNOWN_CHILD_ID.equals(documentId) || NEW_CHILD_ID.equals(documentId))
            || DIRECTORY_ID.equals(parentDocumentId)
                && (KNOWN_CHILD_ID.equals(documentId) || NEW_CHILD_ID.equals(documentId));
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        deleteCalls.incrementAndGet();
        try {
            if (DIRECTORY_ID.equals(documentId)) clearDirectory();
            else Files.deleteIfExists((FILE_NAME.equals(documentId) ? document() : directoryDocument(documentId)).toPath());
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        createCalls.incrementAndGet();
        if (!ROOT_ID.equals(parentDocumentId) || !FILE_NAME.equals(displayName)
                || Document.MIME_TYPE_DIR.equals(mimeType)) {
            throw new FileNotFoundException("Only the fixed test document may be created");
        }
        try {
            if (!document().createNewFile()) throw new IOException("Test document already exists");
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
        return FILE_NAME;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = FILE_NAME.equals(documentId) ? document() : directoryDocument(documentId);
        if (mode.contains("w")) {
            writeOpens.incrementAndGet();
        } else {
            CountDownLatch release = readRelease;
            readEntered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new FileNotFoundException("The fixture read was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("The fixture read was interrupted");
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"reset".equals(method) && !"read".equals(method) && !"stats".equals(method)
                && !"blockRead".equals(method) && !"releaseRead".equals(method)
                && !"directoryReset".equals(method) && !"directoryAdd".equals(method)
                && !"directoryEdit".equals(method) && !"directoryRead".equals(method)
                && !"directoryStats".equals(method) && !"directoryFailListing".equals(method)) {
            return super.call(method, arg, extras);
        }
        try {
            int targetUid = getContext().getPackageManager()
                .getApplicationInfo(SafProviderGrantReceiver.TARGET_PACKAGE, 0).uid;
            if (Binder.getCallingUid() != Process.myUid() && Binder.getCallingUid() != targetUid) {
                throw new SecurityException("Only the fixture owner and target application may control documents");
            }
            File file = document();
            Bundle result = new Bundle();
            switch (method) {
                case "reset":
                    readRelease.countDown();
                    readRelease = new CountDownLatch(0);
                    readEntered = new CountDownLatch(1);
                    File directory = file.getParentFile();
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Cannot create test document directory");
                    }
                    clearDirectory();
                    failDirectoryListing = false;
                    if (extras != null && extras.getBoolean("empty")) {
                        Files.deleteIfExists(file.toPath());
                    } else {
                        byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
                        if (bytes == null) throw new IllegalArgumentException("Missing fixture bytes");
                        Files.write(file.toPath(), bytes);
                    }
                    writeOpens.set(0);
                    createCalls.set(0);
                    deleteCalls.set(0);
                    break;
                case "directoryReset":
                    clearDirectory();
                    if (!directoryDocument(DIRECTORY_ID).mkdirs()) throw new IOException("Cannot create fixture folder");
                    Files.write(directoryDocument(KNOWN_CHILD_ID).toPath(), extras.getByteArray("bytes"));
                    failDirectoryListing = false;
                    deleteCalls.set(0);
                    break;
                case "directoryAdd":
                    Files.write(directoryDocument(NEW_CHILD_ID).toPath(), extras.getByteArray("bytes"));
                    break;
                case "directoryEdit":
                    Files.write(directoryDocument(KNOWN_CHILD_ID).toPath(), extras.getByteArray("bytes"));
                    break;
                case "directoryRead":
                    result.putByteArray("bytes", Files.readAllBytes(directoryDocument(
                        extras != null && extras.getBoolean("newChild") ? NEW_CHILD_ID : KNOWN_CHILD_ID).toPath()));
                    break;
                case "directoryStats":
                    result.putBoolean("exists", directoryDocument(DIRECTORY_ID).isDirectory());
                    result.putBoolean("knownExists", directoryDocument(KNOWN_CHILD_ID).isFile());
                    result.putBoolean("newExists", directoryDocument(NEW_CHILD_ID).isFile());
                    result.putInt("deleteCalls", deleteCalls.get());
                    break;
                case "directoryFailListing":
                    failDirectoryListing = true;
                    break;
                case "read":
                    result.putByteArray("bytes", Files.readAllBytes(file.toPath()));
                    break;
                case "blockRead":
                    readEntered = new CountDownLatch(1);
                    readRelease = new CountDownLatch(1);
                    break;
                case "releaseRead":
                    readRelease.countDown();
                    break;
                case "stats":
                    result.putBoolean("readBlocked", readEntered.getCount() == 0 && readRelease.getCount() > 0);
                    result.putInt("writeOpens", writeOpens.get());
                    result.putInt("createCalls", createCalls.get());
                    result.putBoolean("exists", file.isFile());
                    break;
                default:
                    throw new IllegalArgumentException(method);
            }
            return result;
        } catch (IOException | android.content.pm.PackageManager.NameNotFoundException exception) {
            throw new IllegalStateException("Cannot access disposable provider fixture", exception);
        }
    }

    private MatrixCursor documents(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        });
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = ROOT_ID.equals(id);
        File file = root || FILE_NAME.equals(id) ? document() : directoryDocument(id);
        if (!root && !file.exists()) throw new FileNotFoundException(id);
        boolean directory = root || file.isDirectory();
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, root ? "VSCodroid test documents" : file.getName())
            .add(Document.COLUMN_MIME_TYPE, directory ? Document.MIME_TYPE_DIR : "text/plain")
            .add(Document.COLUMN_FLAGS, directory ? Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE
                : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE)
            .add(Document.COLUMN_SIZE, directory ? 0L : file.length())
            .add(Document.COLUMN_LAST_MODIFIED, directory ? 0L : file.lastModified());
    }
}
