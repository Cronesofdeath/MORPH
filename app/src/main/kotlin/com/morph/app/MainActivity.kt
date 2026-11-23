package com.morph.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.morph.app.ui.MorphApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity:ComponentActivity() {
    private val model:StudioModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(savedInstanceState==null) accept(intent)
        setContent { MorphApp(model) }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); accept(intent) }
    private fun accept(intent:Intent?) {
        if(intent?.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) return
        val incoming=intent ?: return
        try {
            val clip = incoming.clipData
            val uris = buildList {
                if (clip != null) {
                    repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) }
                }
                if (incoming.action == Intent.ACTION_SEND_MULTIPLE) {
                    val streams: List<*>? = IntentCompat.getParcelableArrayListExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    streams?.filterIsInstance<Uri>()?.let(::addAll)
                } else {
                    IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                }
            }.distinct()
            if (uris.isNotEmpty()) {
                model.pick(uris, false)
            } else {
                val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                text?.toString()?.let(model::paste)
            }
        } catch (e: RuntimeException) {
            model.events.tryEmit("The shared item could not be read")
        }
    }
}
