package dev.ideatab.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FileLabelsTest {
    @Test fun `Windows mixed separators and drive case produce relative directory`() {
        assertEquals("src/module", FileLabels.directory("C:\\Work\\Project\\src\\module\\A.kt", "c:/Work/Project"))
        assertEquals("", FileLabels.directory("C:\\Work\\Project\\A.kt", "C:/Work/Project/"))
    }
    @Test fun `similarly named sibling directory remains absolute`() {
        assertEquals("C:/Work/ProjectOther/src", FileLabels.directory("C:/Work/ProjectOther/src/A.kt", "C:/Work/Project"))
    }
    @Test fun `Unix paths remain case sensitive and UNC paths retain root`() {
        assertEquals("/work/project/src", FileLabels.directory("/work/project/src/A.kt", "/work/Project"))
        assertEquals("src", FileLabels.directory("\\\\server\\share\\project\\src\\A.kt", "//SERVER/share/project"))
        assertEquals("/other/src", FileLabels.directory("/other/src/A.kt", null))
        assertEquals("src", FileLabels.directory("/src/A.kt", "/"))
    }
}
