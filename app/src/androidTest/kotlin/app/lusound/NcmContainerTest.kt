package app.lusound

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.ncm.NcmException
import app.lusound.ncm.readNcmHeader
import java.io.File
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NcmContainerTest {
    @Test fun rejectDamagedHeadersAndOversizedAllocations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File(context.getExternalFilesDir(null), "ncm-sample.ncm")
        // A missing sample must read as a missing sample, not as a decode failure.
        check(sample.isFile) { "请先把有权使用的 NCM 原文件推送至应用外部 files/ncm-sample.ncm（至少 70 KB、时长超过 3 秒且含封面）" }
        val bytes = sample.readBytes()
        val header = readNcmHeader(bytes.inputStream(), bytes.size.toLong())
        val cases = linkedMapOf(
            "invalid magic" to bytes.copyOf().apply { this[0] = 0 },
            "oversized key block" to bytes.copyOf().apply { for (index in 10..13) this[index] = 0xff.toByte() },
            "damaged key block" to bytes.copyOf().apply { this[14] = (this[14].toInt() xor 127).toByte() },
            "truncated before the audio" to bytes.copyOf(header.audioOffset.toInt() - 1),
        )
        for ((label, damaged) in cases) {
            try {
                readNcmHeader(damaged.inputStream(), damaged.size.toLong())
                fail("Damaged NCM must fail explicitly: $label")
            } catch (expected: NcmException) {
                // The parser's own type, so a formatting problem cannot be mistaken for exhausted input.
            }
        }
    }
}
