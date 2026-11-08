package com.vscodroid.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.provider.DocumentsContract
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class SafDirectoryDeleteTest {
    @TempDir lateinit var root: File
    private lateinit var mirror: File
    private lateinit var engine: SafSyncEngine
    private val tree = mockk<Uri>(relaxed = true)
    private val uris = mutableMapOf<String, Uri>()
    private val children = mutableMapOf<String, MutableList<Entry>>()
    private val kept = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var failedListing: String? = null
    private var nullListing: String? = null
    private var unreadable: String? = null
    private var listingStatus: String? = null

    private data class Entry(
        val id: String,
        val name: String,
        val directory: Boolean = false,
        var text: String = "original",
    )

    @BeforeEach fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(any(), any()) } answers {
            uri("children:${secondArg<String>()}")
        }
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } answers {
            uri("document:${secondArg<String>()}")
        }
        every { DocumentsContract.getDocumentId(any()) } answers {
            key(firstArg()).removePrefix("document:")
        }
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            deleted += key(secondArg()).removePrefix("document:")
            true
        }
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val key = key(firstArg())
            val id = key.substringAfter(':')
            if (key.startsWith("children:") && id == failedListing) throw IOException("offline")
            if (key.startsWith("children:") && id == nullListing) null else {
                cursor(if (key.startsWith("children:") && listingStatus != null && id == "folder-id")
                    emptyList() else if (key.startsWith("children:")) children[id].orEmpty() else
                    children.values.flatten().filter { it.id == id })
            }
        }
        every { resolver.openInputStream(any()) } answers {
            val id = key(firstArg()).removePrefix("document:")
            if (id == unreadable) throw IOException("offline")
            ByteArrayInputStream(children.values.flatten().first { it.id == id }.text.toByteArray())
        }
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns File(root, "files").apply { mkdirs() }
        engine = SafSyncEngine(context)
        engine.onKeptOnDevice = { file, _, _ -> kept += file.name }
        mirror = File(root, "mirror").apply { mkdirs() }
        children["root"] = mutableListOf(Entry("folder-id", "folder", directory = true))
        children["folder-id"] = mutableListOf(Entry("known-id", "known.txt"))
    }

    @AfterEach fun tearDown() = unmockkAll()

    private fun uri(key: String): Uri = uris.getOrPut(key) { mockk(relaxed = true) }
    private fun key(uri: Uri): String = uris.entries.first { it.value === uri }.key

    private fun cursor(entries: List<Entry>): Cursor {
        var row = -1
        return mockk<Cursor>(relaxed = true).apply {
            val status = mockk<Bundle>(relaxed = true)
            every { status.getBoolean(DocumentsContract.EXTRA_LOADING) } answers {
                listingStatus == DocumentsContract.EXTRA_LOADING
            }
            every { status.containsKey(DocumentsContract.EXTRA_ERROR) } answers {
                listingStatus == DocumentsContract.EXTRA_ERROR
            }
            every { extras } returns status
            every { moveToNext() } answers { ++row < entries.size }
            every { moveToFirst() } answers { row = 0; entries.isNotEmpty() }
            every { getColumnIndexOrThrow(any()) } answers { column(firstArg<String>()) }
            every { getColumnIndex(any()) } answers { column(firstArg<String>()) }
            every { this@apply.isNull(any()) } returns false
            every { getString(any()) } answers {
                val entry = entries[row]
                when (firstArg<Int>()) {
                    0 -> entry.id
                    1 -> entry.name
                    2 -> if (entry.directory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
                    else -> error("Not a string column")
                }
            }
            every { getLong(any()) } answers {
                if (firstArg<Int>() == 3) entries[row].text.length.toLong() else 1_700_000_000_000L
            }
        }
    }

    private fun column(name: String): Int = when (name) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> 0
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> 1
        DocumentsContract.Document.COLUMN_MIME_TYPE -> 2
        DocumentsContract.Document.COLUMN_SIZE -> 3
        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 4
        else -> -1
    }

    private fun open() = runBlocking { engine.initialSync(tree, mirror) { _, _ -> } }
    private fun queueDelete() {
        val folder = File(mirror, "folder")
        assertTrue(folder.deleteRecursively())
        engine.handleMirrorEvent(FileObserver.DELETE or 0x40000000, folder, mirror, tree)
    }
    private fun drain() = engine.runWriteBackLoop { false }
    private fun assertRetained() {
        assertEquals(emptyList<String>(), deleted, "The provider received a recursive delete")
        assertEquals(listOf("folder"), kept, "The retained directory was not reported")
    }

    @Test fun `a child added after the delete was queued keeps the provider directory`() {
        open()
        queueDelete()
        children.getValue("folder-id") += Entry("new-id", "new.txt", text = "only on device")
        drain()
        assertRetained()
    }

    @Test fun `a nested child added after opening keeps the provider directory`() {
        children.getValue("folder-id") += Entry("nested-id", "nested", directory = true)
        children["nested-id"] = mutableListOf()
        open()
        children.getValue("nested-id") += Entry("new-id", "new.txt")
        queueDelete()
        drain()
        assertRetained()
    }

    @Test fun `a changed descendant with an unchanged stamp keeps the provider directory`() {
        open()
        children.getValue("folder-id").first().text = "replaced"
        queueDelete()
        drain()
        assertRetained()
    }

    @Test fun `a replacement document with the same name and bytes keeps the directory`() {
        open()
        children["folder-id"] = mutableListOf(Entry("replacement-id", "known.txt"))
        queueDelete()
        drain()
        assertRetained()
    }

    @Test fun `a failed child listing keeps the provider directory`() {
        open()
        queueDelete()
        failedListing = "folder-id"
        drain()
        assertRetained()
    }

    @Test fun `a missing child cursor keeps the provider directory`() {
        open()
        queueDelete()
        nullListing = "folder-id"
        drain()
        assertRetained()
    }

    @Test fun `an unreadable known descendant keeps the provider directory`() {
        open()
        queueDelete()
        unreadable = "known-id"
        drain()
        assertRetained()
    }

    @Test fun `a child listing still loading keeps the provider directory`() {
        open()
        queueDelete()
        listingStatus = DocumentsContract.EXTRA_LOADING
        drain()
        assertRetained()
    }

    @Test fun `a child listing with a provider error keeps the directory`() {
        open()
        queueDelete()
        listingStatus = DocumentsContract.EXTRA_ERROR
        drain()
        assertRetained()
    }

    @Test fun `a directory containing only unchanged known descendants is deleted`() {
        children.getValue("folder-id") += Entry("nested-id", "nested", directory = true)
        children["nested-id"] = mutableListOf(Entry("nested-file-id", "nested.txt"))
        open()
        assertTrue(File(mirror, "folder/nested/nested.txt").isFile)
        queueDelete()
        drain()
        assertEquals(listOf("folder-id"), deleted)
        assertTrue(kept.isEmpty())
    }

    @Test fun `a known empty directory is deleted`() {
        children["folder-id"] = mutableListOf()
        open()
        queueDelete()
        drain()
        assertEquals(listOf("folder-id"), deleted)
    }

    @Test fun `an ordinary file delete still reaches the provider`() {
        open()
        val file = File(mirror, "folder/known.txt")
        assertTrue(file.delete())
        assertFalse(file.exists())
        engine.handleMirrorEvent(FileObserver.DELETE, file, mirror, tree)
        drain()
        assertEquals(listOf("known-id"), deleted)
        assertTrue(kept.isEmpty())
    }
}
