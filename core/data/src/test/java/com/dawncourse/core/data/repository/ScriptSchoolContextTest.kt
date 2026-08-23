package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.ScriptSchoolContext

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptSchoolContextTest {
    @Test
    fun `buildSchoolId prefers normalized school name`() {
        val schoolId = ScriptSchoolContext.buildSchoolId(
            schoolName = " 泰山科技学院 ",
            schoolSystemType = "zhengfang",
            sourceUrl = "https://jw.tskjxy.edu.cn/path"
        )

        assertEquals("school:zf:泰山科技学院", schoolId)
    }

    @Test
    fun `buildSchoolId falls back to source host`() {
        val schoolId = ScriptSchoolContext.buildSchoolId(
            schoolName = "",
            schoolSystemType = "",
            sourceUrl = "https://jw.example.edu.cn/path"
        )

        assertEquals("school:unknown:jw.example.edu.cn", schoolId)
    }
}
