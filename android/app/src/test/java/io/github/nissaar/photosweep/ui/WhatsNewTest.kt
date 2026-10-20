package io.github.nissaar.photosweep.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTest {

    @Test
    fun `wrapped lines are joined and paragraphs kept`() {
        val wrapped = "Deleting a long list no longer\ntimes out.\n\nSecond paragraph\nhere.\n"

        assertEquals(
            "Deleting a long list no longer times out.\n\nSecond paragraph here.",
            unwrap(wrapped),
        )
    }
}

class FolderOfTest {

    @Test
    fun `folder is the path without the file name`() {
        assertEquals("/Photos/2024/Trip", folderOf("Photos/2024/Trip/IMG_1.jpg"))
        assertEquals("/Photos", folderOf("/Photos/IMG_1.jpg"))
        assertEquals("/", folderOf("IMG_1.jpg"))
    }
}
