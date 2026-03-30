package io.github.thevellichor.samsungopenring.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tasker Event plugin edit screen.
 * No configuration needed — just confirms the event type.
 */
class EditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Ring Gesture Event"
            textSize = 20f
        })
        layout.addView(TextView(this).apply {
            text = "\nThis event fires when a double-pinch gesture is detected on your Samsung Galaxy Ring.\n\nNo configuration needed."
            textSize = 14f
        })

        setContentView(layout)

        // Return result immediately — no config needed
        val resultIntent = Intent().apply {
            putExtra(
                "com.twofortyfouram.locale.intent.extra.BLURB",
                "Ring double-pinch gesture"
            )
            putExtra(
                "com.twofortyfouram.locale.intent.extra.BUNDLE",
                Bundle()
            )
        }
        setResult(RESULT_OK, resultIntent)
    }
}
