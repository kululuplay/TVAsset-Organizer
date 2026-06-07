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
import android.widget.ImageView
import android.widget.TextView
import com.iptv.player.R
import com.iptv.player.data.model.ContentType
import com.iptv.player.databinding.ActivityContentManagerBinding
import com.iptv.player.ui.common.BaseActivity

class ContentManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityContentManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContentManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val first = addSection(
            R.drawable.ic_tv,
            getString(R.string.content_manager_live),
            getString(R.string.content_manager_live_hint),
            ContentType.LIVE
        )
        addSection(
            R.drawable.ic_movie,
            getString(R.string.content_manager_movies),
            getString(R.string.content_manager_movies_hint),
            ContentType.VOD
        )
        addSection(
            R.drawable.ic_series,
            getString(R.string.content_manager_series),
            getString(R.string.content_manager_series_hint),
            ContentType.SERIES
        )

        first.post { first.requestFocus() }
    }

    private fun addSection(
        iconRes: Int,
        title: String,
        subtitle: String,
        type: ContentType
    ): android.view.View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_content_section, binding.sectionContainer, false)
        row.findViewById<ImageView>(R.id.csIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.csTitle).text = title
        row.findViewById<TextView>(R.id.csSubtitle).text = subtitle
        row.setOnClickListener {
            startActivity(
                Intent(this, CategoryManagerActivity::class.java)
                    .putExtra(CategoryManagerActivity.EXTRA_TYPE, type.name)
            )
        }
        binding.sectionContainer.addView(row)
        return row
    }
}
