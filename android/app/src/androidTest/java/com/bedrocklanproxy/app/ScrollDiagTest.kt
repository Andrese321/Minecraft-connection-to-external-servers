package com.bedrocklanproxy.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Diagnóstico del desplazamiento de la pantalla principal: reproduce lo que hace
 * un usuario (escribir con el teclado, iniciar, deslizar, girar) y escribe en
 * logcat (etiquetas DIAG y SHOT) medidas y capturas reducidas en base64.
 */
@RunWith(AndroidJUnit4::class)
class ScrollDiagTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val pkg = inst.targetContext.packageName
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Test
    fun diagnosticoDesplazamiento() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            inst.uiAutomation.grantRuntimePermission(pkg, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        device.setOrientationNatural()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        pause(2000)
        dump("1-inicio", shot = true)

        // Escribir con el teclado como haría el usuario.
        device.findObject(By.res(pkg, "host")).click()
        pause(1500)
        device.findObject(By.res(pkg, "host")).text = "172.96.172.240"
        dump("2-teclado-en-host")
        device.findObject(By.res(pkg, "port")).click()
        device.findObject(By.res(pkg, "port")).text = "25776"
        device.findObject(By.res(pkg, "name")).click()
        pause(1000)
        device.findObject(By.res(pkg, "name")).text = "Hardlands"
        pause(1000)
        dump("3-teclado-en-nombre", shot = true)

        // Pulsar Iniciar (con el teclado aún abierto, como en el móvil).
        scenario.onActivity { it.findViewById<View>(R.id.btnStart).performClick() }
        pause(3000)
        dump("4-tras-iniciar", shot = true)

        device.pressBack() // cierra el teclado si sigue abierto
        pause(2000)
        dump("5-teclado-cerrado", shot = true)

        // Deslizar hacia abajo = subir el contenido.
        swipeDown()
        pause(500)
        dump("6-justo-tras-deslizar")
        pause(3000)
        dump("7-3s-despues", shot = true)

        // Horizontal.
        device.setOrientationLeft()
        pause(3000)
        dump("8-horizontal", shot = true)
        swipeDown()
        pause(500)
        dump("9-horizontal-tras-deslizar")
        pause(3000)
        dump("10-horizontal-3s-despues", shot = true)

        scenario.onActivity { it.findViewById<View>(R.id.btnStart).performClick() } // detener
        pause(1000)
        device.setOrientationNatural()
        scenario.close()
    }

    private fun swipeDown() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, (h * 0.35).toInt(), w / 2, (h * 0.85).toInt(), 25)
    }

    private fun pause(ms: Long) {
        device.waitForIdle()
        SystemClock.sleep(ms)
    }

    private fun dump(label: String, shot: Boolean = false) {
        scenario.onActivity { a ->
            val sv = a.findViewById<ScrollView>(R.id.scroll)
            val child = sv.getChildAt(0)
            val host = a.findViewById<View>(R.id.host)
            val loc = IntArray(2).also { host.getLocationOnScreen(it) }
            val svLoc = IntArray(2).also { sv.getLocationOnScreen(it) }
            val extent = sv.height - sv.paddingTop - sv.paddingBottom
            val range = maxOf(0, child.height - extent)
            val insets = sv.rootWindowInsets
            val ime = if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else -1
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
                insets.getInsets(WindowInsets.Type.systemBars()).toString()
            } else "?"
            val focus = a.currentFocus?.let { v ->
                try { a.resources.getResourceEntryName(v.id) } catch (_: Exception) { v.javaClass.simpleName }
            }
            Log.i(
                TAG,
                "$label | scrollY=${sv.scrollY} rango=$range svTop=${svLoc[1]} svH=${sv.height} " +
                    "padTop=${sv.paddingTop} padBottom=${sv.paddingBottom} hijoH=${child.height} " +
                    "hostY=${loc[1]} foco=$focus ime=$ime barras=$bars " +
                    "rootH=${a.window.decorView.height} decorScrollY=${a.window.decorView.scrollY}",
            )
        }
        if (shot) screenshot(label)
    }

    private fun screenshot(label: String) {
        val full = inst.uiAutomation.takeScreenshot() ?: return
        val w = 240
        val h = full.height * w / full.width
        val small = Bitmap.createScaledBitmap(full, w, h, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 55, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        Log.i(SHOT, "BEGIN $label")
        b64.chunked(3000).forEach { Log.i(SHOT, it) }
        Log.i(SHOT, "END $label")
    }

    private companion object {
        const val TAG = "DIAG"
        const val SHOT = "SHOT"
    }
}
