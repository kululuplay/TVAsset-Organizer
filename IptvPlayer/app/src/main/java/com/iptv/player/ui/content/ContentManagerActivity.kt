/*
 * ContentManagerActivity.kt
 * The Content Manager hub. Lets the user pick which content type to organise —
 * Live TV, Movies or Series — and opens the per-type category editor. Live also
 * drills further into a per-category channel editor (from CategoryManager).
 */
package com.iptv.player.ui.content

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.iptv.player.R
import com.iptv.player.data.model.ContentType
import com.iptv.player.databinding.ActivityContentManagerBinding
import com.iptv.player.ui.common.BaseActivity
import java.util.Locale

class ContentManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityContentManagerBinding
    private val sectionViews = linkedMapOf<ContentType, View>()
    private var lastFocusedType = ContentType.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContentManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setAccessibilityPaneTitle(
            binding.root,
            getString(R.string.content_manager_title)
        )
        ViewCompat.setAccessibilityHeading(binding.cmHubTitle, true)

        lastFocusedType = savedInstanceState
            ?.getString(STATE_FOCUSED_TYPE)
            ?.let { value -> runCatching { ContentType.valueOf(value) }.getOrNull() }
            ?: ContentType.LIVE

        addSection(
            R.drawable.ic_tv,
            R.drawable.bg_badge_live,
            getString(R.string.content_manager_live),
            getString(R.string.content_manager_live_hint),
            ContentType.LIVE,
            1
        )
        addSection(
            R.drawable.ic_movie,
            R.drawable.bg_badge_movies,
            getString(R.string.content_manager_movies),
            getString(R.string.content_manager_movies_hint),
            ContentType.VOD,
            2
        )
        addSection(
            R.drawable.ic_series,
            R.drawable.bg_badge_series,
            getString(R.string.content_manager_series),
            getString(R.string.content_manager_series_hint),
            ContentType.SERIES,
            3
        )

        configureFocusGraph()
        binding.root.post {
            sectionViews[lastFocusedType]?.requestFocus()
                ?: sectionViews[ContentType.LIVE]?.requestFocus()
        }
    }

    private fun addSection(
        iconRes: Int,
        badgeRes: Int,
        title: String,
        subtitle: String,
        type: ContentType,
        order: Int
    ) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_content_section, binding.sectionContainer, false)
        row.id = View.generateViewId()
        row.findViewById<View>(R.id.csAccent).setBackgroundResource(badgeRes)
        row.findViewById<ImageView>(R.id.csIcon).apply {
            setImageResource(iconRes)
            setBackgroundResource(badgeRes)
        }
        row.findViewById<TextView>(R.id.csTitle).text = title
        row.findViewById<TextView>(R.id.csSubtitle).text = subtitle
        row.findViewById<TextView>(R.id.csOrder).text =
            String.format(Locale.ROOT, "%02d", order)
        row.contentDescription = listOf(
            title,
            subtitle,
            getString(R.string.content_manager_edit)
        ).joinToString(". ")
        row.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lastFocusedType = type
        }
        row.setOnClickListener {
            startActivity(
                Intent(this, CategoryManagerActivity::class.java)
                    .putExtra(CategoryManagerActivity.EXTRA_TYPE, type.name)
            )
        }
        binding.sectionContainer.addView(row)
        sectionViews[type] = row
    }

    /**
     * Android's geometric focus search can jump to a distant view after a scale
     * animation. An explicit vertical chain makes repeated D-pad presses stable
     * and keeps horizontal presses inside the single destination rail.
     */
    private fun configureFocusGraph() {
        val rows = sectionViews.values.toList()
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: row.id
            row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: row.id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val focusedType = sectionViews.entries.firstOrNull { it.value.hasFocus() }?.key
            ?: lastFocusedType
        outState.putString(STATE_FOCUSED_TYPE, focusedType.name)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val STATE_FOCUSED_TYPE = "state_focused_type"
    }
}
