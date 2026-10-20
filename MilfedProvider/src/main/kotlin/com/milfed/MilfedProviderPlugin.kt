package com.milfed

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * The site serves the whole video only to a member session; an anonymous
 * session gets the 20 second trailer. The plugin therefore offers a place to
 * paste the user's own session cookie, which is what turns the preview into
 * the full video.
 *
 * The session is read from the browser: log in at milfed.com, open the
 * developer tools, and copy the value of the Cookie header. It is the
 * user's own session - the plugin does not create, guess or share one, and
 * it never leaves the device.
 */
@CloudstreamPlugin
class MilfedProviderPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(MilfedProvider(context.applicationContext))
        openSettings = { ctx -> showSessionDialog(ctx) }
    }

    private fun showSessionDialog(context: Context) {
        val prefs = context.getSharedPreferences("milfed", Context.MODE_PRIVATE)
        val input = EditText(context).apply {
            setText(prefs.getString(KEY_SESSION, "") ?: "")
            hint = "instance_token=...; app_session_id=...; __s=..."
            setSingleLine(false)
            maxLines = 6
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(context)
            .setTitle("Milfed - member session")
            .setMessage(
                "Copy the Cookie header from a logged-in milfed.com page and paste it " +
                    "here to play the full video. Leave it empty for the 20 second preview."
            )
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                prefs.edit().putString(KEY_SESSION, value).apply()
                MilfedApi.memberSession = value.ifBlank { null }
                Log.i("MilfedProvider", "member session stored (${value.length} chars)")
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.edit().remove(KEY_SESSION).apply()
                MilfedApi.memberSession = null
                Log.i("MilfedProvider", "member session cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val KEY_SESSION = "session"
    }
}
