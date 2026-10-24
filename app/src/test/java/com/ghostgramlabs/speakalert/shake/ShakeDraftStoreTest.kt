package com.ghostgramlabs.speakalert.shake

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShakeDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only finished takes appear and survive reopening the store`() {
        val store = ShakeDraftStore(temporary.root)
        val recording = store.newRecording().apply { writeText("audio") }
        assertTrue(store.list().isEmpty())
        val finished = store.finish(recording)
        val reopened = ShakeDraftStore(temporary.root)
        assertEquals(listOf(finished), reopened.list())
        assertEquals("audio", reopened.find(finished.nameWithoutExtension)?.readText())
        assertTrue(reopened.delete(finished.nameWithoutExtension))
        assertTrue(store.list().isEmpty())
    }

    @Test fun `an inbox id cannot escape its directory`() {
        val outside = temporary.newFile("private.m4a").apply { writeText("private") }
        val store = ShakeDraftStore(temporary.root)
        assertNull(store.find("../private"))
        assertFalse(store.delete("../private"))
        assertTrue(outside.exists())
    }

    @Test fun `multiple takes are retained without overwriting`() {
        val store = ShakeDraftStore(temporary.root)
        val first = store.finish(store.newRecording().apply { writeText("first") })
        val second = store.finish(store.newRecording().apply { writeText("second") })
        assertNotEquals(first, second)
        assertEquals(2, store.list().size)
    }

    @Test(expected = IllegalStateException::class)
    fun `empty takes are not published`() {
        val store = ShakeDraftStore(temporary.root)
        store.finish(store.newRecording().apply { createNewFile() })
    }
}
