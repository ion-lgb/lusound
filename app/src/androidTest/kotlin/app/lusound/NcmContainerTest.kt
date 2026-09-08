package app.lusound

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lusound.ncm.readNcmHeader
import java.io.File
import java.io.IOException
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NcmContainerTest {
    @Test fun rejectDamagedHeadersAndOversizedAllocations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File(context.getExternalFilesDir(null), "ncm-sample.ncm").readBytes()
        val header = readNcmHeader(sample.inputStream(), sample.size.toLong())
        val invalidMagic = sample.copyOf().apply { this[0] = 0 }
        val oversized = sample.copyOf().apply { for (index in 10..13) this[index] = 0xff.toByte() }
        val damagedKey = sample.copyOf().apply { this[14] = (this[14].toInt() xor 127).toByte() }
        val truncated = sample.copyOf(header.audioOffset.toInt() - 1)
        for (bytes in listOf(invalidMagic, oversized, damagedKey, truncated)) {
            try { readNcmHeader(bytes.inputStream(), bytes.size.toLong()); fail("Damaged NCM must fail explicitly") }
            catch (_: IOException) { }
        }
    }
}
