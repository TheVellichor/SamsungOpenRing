package io.github.thevellichor.samsungopenring.tasker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Tasker Action plugin edit screen.
 * Lets user choose: enable gestures, disable gestures, or toggle.
 */
class ActionEditActivity : Activity() {

    companion object {
        const val EXTRA_ACTION = "ring_action"
        const val ACTION_ENABLE = "enable"
        const val ACTION_DISABLE = "disable"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Ring Gesture Control"
            textSize = 20f
        })
        layout.addView(TextView(this).apply {
            text = "\nChoose action:"
            textSize = 14f
        })

        val radioGroup = RadioGroup(this)
        val enableBtn = RadioButton(this).apply {
            text = "Enable gesture monitoring"
            id = 1
            isChecked = true
        }
        val disableBtn = RadioButton(this).apply {
            text = "Disable gesture monitoring"
            id = 2
        }
        radioGroup.addView(enableBtn)
        radioGroup.addView(disableBtn)
        layout.addView(radioGroup)

        setContentView(layout)

        // Set result when user navigates back
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val action = if (checkedId == 1) ACTION_ENABLE else ACTION_DISABLE
            val blurb = if (checkedId == 1) "Enable ring gestures" else "Disable ring gestures"

            val bundle = Bundle().apply {
                putString(EXTRA_ACTION, action)
            }

            setResult(RESULT_OK, Intent().apply {
                putExtra("com.twofortyfouram.locale.intent.extra.BLURB", blurb)
                putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", bundle)
            })
        }

        // Default result
        setResult(RESULT_OK, Intent().apply {
            putExtra("com.twofortyfouram.locale.intent.extra.BLURB", "Enable ring gestures")
            putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", Bundle().apply {
                putString(EXTRA_ACTION, ACTION_ENABLE)
            })
        })
    }
}
