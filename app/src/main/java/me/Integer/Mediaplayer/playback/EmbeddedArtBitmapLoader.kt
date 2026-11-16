package me.Integer.Mediaplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import me.Integer.Mediaplayer.library.EmbeddedArt
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Gives the media session its artwork. The artwork URI of a queue item is the audio file
 * itself, so "loading" it means reading the cover embedded in that file. Work runs on one
 * background thread; a file without a cover fails the request, which shows no artwork.
 */
class EmbeddedArtBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean {
        return true
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executor.submit(Callable<Bitmap> {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("artwork is not an image")
        })
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executor.submit(Callable<Bitmap> {
            EmbeddedArt.decode(appContext, uri, ART_PX) ?: throw IOException("no embedded cover in $uri")
        })
    }

    fun shutdown() {
        executor.shutdown()
    }

    private companion object {
        const val ART_PX = 512
    }
}
