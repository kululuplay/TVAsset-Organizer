/*
 * PlayerDialogs.kt
 * Modern, TV-first replacements for the stock AlertDialogs used across the two
 * player screens. Provides a resume prompt (with the exact stopped-at timestamp)
 * and a reusable single-choice option sheet (audio / subtitle / sleep / delay /
 * options menu). All rows are D-pad focusable; selection is marked with a check.
 */
package com.iptv.player.ui.player

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R

object PlayerDialogs {

    /** A single choice in an option sheet. */
    data class Option(val label: String, val selected: Boolean = false)

    /** Rows shown before the list starts scrolling, to keep tall lists on-screen. */
    private const val MAX_VISIBLE_ROWS = 7

    /**
     * Modern resume prompt showing the exact position the user stopped at.
     * [positionText] should already be formatted (e.g. "1:04:37").
     */
    fun showResume(
        activity: Activity,
        positionText: String,
        onResume: () -> Unit,
        onStartOver: () -> Unit,
    ): Dialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_resume, null)
        view.findViewById<TextView>(R.id.resumeTime).text = positionText

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        val resumeButton = view.findViewById<View>(R.id.resumeButton)
        resumeButton.setOnClickListener {
            dialog.dismiss()
            onResume()
        }
        view.findViewById<View>(R.id.startOverButton).setOnClickListener {
            dialog.dismiss()
            onStartOver()
        }

        dialog.show()
        resumeButton.requestFocus()
        return dialog
    }

    /**
     * Modern single-choice option sheet. [onSelected] receives the index into
     * [options]. The currently-selected option (if any) is focused on open.
     */
    fun showOptions(
        activity: Activity,
        title: CharSequence,
        options: List<Option>,
        onSelected: (Int) -> Unit,
    ): Dialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_player_list, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title

        val list = view.findViewById<RecyclerView>(R.id.optionList)
        list.layoutManager = LinearLayoutManager(activity)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        list.adapter = OptionAdapter(options) { index ->
            dialog.dismiss()
            onSelected(index)
        }

        // Cap the height for long lists (e.g. delay steps) so the sheet never
        // grows past the screen; the RecyclerView scrolls internally instead.
        if (options.size > MAX_VISIBLE_ROWS) {
            val rowPx = activity.resources.getDimensionPixelSize(R.dimen.dialog_option_height)
            val params = list.layoutParams
            params.height = rowPx * MAX_VISIBLE_ROWS
            list.layoutParams = params
        }

        val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        dialog.show()
        list.post {
            list.scrollToPosition(selectedIndex)
            list.findViewHolderForAdapterPosition(selectedIndex)?.itemView?.requestFocus()
        }
        return dialog
    }

    private class OptionAdapter(
        private val items: List<Option>,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<OptionAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player_option, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val label: TextView = itemView.findViewById(R.id.optionLabel)
            private val check: ImageView = itemView.findViewById(R.id.optionCheck)

            fun bind(option: Option) {
                label.text = option.label
                check.visibility = if (option.selected) View.VISIBLE else View.GONE
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(pos)
                }
            }
        }
    }
}
